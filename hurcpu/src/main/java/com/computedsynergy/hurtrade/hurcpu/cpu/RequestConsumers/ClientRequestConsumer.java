/*
 * Copyright 2016 Faisal Thaheem <faisal.ajmal@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.computedsynergy.hurtrade.hurcpu.cpu.RequestConsumers;

import com.computedsynergy.hurtrade.hurcpu.cpu.ClientRequestProcessor;
import com.computedsynergy.hurtrade.hurcpu.cpu.CommodityUpdateProcessor;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.QuoteList;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.PositionModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.CommodityUser;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.Position;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.trade.TradeRequest;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.trade.TradeResponse;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.updates.ClientUpdate;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.UserModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.User;
import com.computedsynergy.hurtrade.sharedcomponents.util.Constants;
import com.computedsynergy.hurtrade.sharedcomponents.util.HurUtil;
import com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.EXPIRY_LOCK_USER_POSITIONS;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.TIMEOUT_LOCK_USER_POSITIONS;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.getLockNameForUserPositions;
import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.getUserPositionsKeyName;
import com.github.jedis.lock.JedisLock;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 */
public class ClientRequestConsumer extends DefaultConsumer {

    private final String officeExhcangeName;
    private final String officeClientRequestQueueName;
    
    private Gson gson = new Gson();

    public ClientRequestConsumer(Channel channel, String officeExchangeName, String officeClientRequestQueueName) {
        super(channel);
        this.officeExhcangeName = officeExchangeName;
        this.officeClientRequestQueueName = officeClientRequestQueueName;
    }

    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
        super.handleDelivery(consumerTag, envelope, properties, body); //To change body of generated methods, choose Tools | Templates.

        long deliveryTag = envelope.getDeliveryTag();

        TradeResponse response = null;
        boolean responseRequired = true;
        String userid = null;
        User user = null;
        
        try {


            
            userid = properties.getUserId();
            if(userid == null){
                responseRequired = false;
                
            }else{
                
                UserModel userModel = new UserModel();
                user = userModel.getByUsername(userid);
                if(user == null){
                    Logger.getLogger(ClientRequestProcessor.class.getName()).log(Level.SEVERE, "Could not resolve user for name: ", userid);
                    responseRequired = false;
                }
                
                try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
                    JedisLock lock = new JedisLock(
                                jedis, 
                                RedisUtil.getLockNameForUserProcessing(user.getUseruuid()),
                                RedisUtil.TIMEOUT_LOCK_USER_PROCESSING, 
                                RedisUtil.EXPIRY_LOCK_USER_PROCESSING
                            );

                    try {
                        if(lock.acquire()){

                            String commandVerb = properties.getType();

                            if(commandVerb.equals("trade")) {

                                TradeRequest request = TradeRequest.fromJson(new String(body));
                                response = new TradeResponse(request);

                                processTradeRequest(response, user);
                            }else if(commandVerb.equals("tradeClosure")) {

                                Map<String,String> cmdParams =  new Gson().fromJson(new String(body), Constants.TYPE_DICTIONARY);
                                closePosition(user, UUID.fromString(cmdParams.get("orderid")));
                            }


                            lock.release();

                        }else{
                            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, "Could not lock user for order processing {0}",  user.getUsername());
                        }
                    } catch (InterruptedException ex) {
                        Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }                
            }

        } catch (Exception ex) {
            Logger.getLogger(ClientRequestProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }

        getChannel().basicAck(deliveryTag, false);
        
        if(!responseRequired || null == userid){
            return;
        }
        
        String clientExchangeName = HurUtil.getClientExchangeName(user.getUseruuid());

        ClientUpdate update = new ClientUpdate(response);
        Gson gson = new Gson();
        String serializedResponse = gson.toJson(update);

        try {
            getChannel().basicPublish(clientExchangeName, "response", null, serializedResponse.getBytes());
        } catch (Exception ex) {

            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private void closePosition(User user, UUID orderId)
    {

        //get the last quoted prices for commodities for this user
        String serializedQuotes = RedisUtil.getInstance().getSeriaizedQuotesForClient(user.getUseruuid());
        QuoteList quotesForClient = gson.fromJson(serializedQuotes, QuoteList.class);


        String userPositionsKeyName = getUserPositionsKeyName(user.getUseruuid());
        Type mapType = new TypeToken<Map<UUID, Position>>(){}.getType();

        try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
            JedisLock lock = new JedisLock(jedis, getLockNameForUserPositions(userPositionsKeyName), TIMEOUT_LOCK_USER_POSITIONS, EXPIRY_LOCK_USER_POSITIONS);
            try{
                if(lock.acquire()){

                    //get client positions
                    Map<UUID, Position> positions = gson.fromJson(jedis.get(userPositionsKeyName),mapType);
                    if(positions.containsKey(orderId)) {


                        Position position = positions.get(orderId);
                        //process close request only if order is open
                        if(position.getOrderState().equalsIgnoreCase(Position.ORDER_STATE_OPEN)) {

                            try {

                                position.setOrderState(Position.ORDER_STATE_PENDING_CLOSE);

                                switch (position.getOrderType()) {
                                    case TradeRequest.REQUEST_TYPE_BUY: {
                                        position.setClosePrice(quotesForClient.get(position.getCommodity()).bid);
                                    }
                                    break;
                                    case TradeRequest.REQUEST_TYPE_SELL: {
                                        position.setClosePrice(quotesForClient.get(position.getCommodity()).ask);
                                    }
                                    break;
                                }

                                //update the current order state to db
                                PositionModel positionModel = new PositionModel();
                                positionModel.saveUpdatePosition(position);

                                //set client positions
                                String serializedPositions = gson.toJson(positions);
                                jedis.set(userPositionsKeyName, serializedPositions);


                            } catch (Exception ex) {
                                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, ex.getMessage(), ex);
                            }
                        }
                    }

                    lock.release();

                }else{
                    Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, "Could not process user positions " + user.getUsername());
                }
            }catch(Exception ex){
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, null, "Could not process user positions " + user.getUsername());
            }
        }
    }
    
    private void processTradeRequest(TradeResponse response, User user)
    {
        
        //todo, check if this commodity is allowed to this customer
        Map<String, CommodityUser> userCommodities = RedisUtil
                                                .getInstance()
                                                .getCachedUserCommodities(user.getUseruuid());
        
        if(!userCommodities.containsKey(response.getRequest().getCommodity())){
            
            response.setResponseCommodityNotAllowed();
            
            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, 
                    "{0} requested invalid commodity: {1}",new Object[]{ user.getUsername() , response.getRequest().getCommodity()});
            return;
        }
        
        
        //get the last quoted prices for commodities for this user
        String serializedQuotes = RedisUtil.getInstance().getSeriaizedQuotesForClient(user.getUseruuid());
        QuoteList quotesForClient = gson.fromJson(serializedQuotes, QuoteList.class);
        
        if(!quotesForClient.containsKey(response.getRequest().getCommodity()))
        {
            response.setResponseCommodityNotAllowed();
            
            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, 
                    "{0} requested unknown commodity: {1}",new Object[]{ user.getUsername() , response.getRequest().getCommodity()});
            return;
        }
        
        String userPositionsKeyName = getUserPositionsKeyName(user.getUseruuid());
        Type mapType = new TypeToken<Map<UUID, Position>>(){}.getType();
        
        try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
            JedisLock lock = new JedisLock(jedis, getLockNameForUserPositions(userPositionsKeyName), TIMEOUT_LOCK_USER_POSITIONS, EXPIRY_LOCK_USER_POSITIONS);
            try{
                if(lock.acquire()){

                    //get client positions
                    Map<UUID, Position> positions = gson.fromJson(jedis.get(userPositionsKeyName),mapType);
                    Position position = null;

                    try {

                        switch (response.getRequest().getRequestType()) {
                            case TradeRequest.REQUEST_TYPE_BUY: {
                                position = new Position(
                                        UUID.randomUUID(), Position.ORDER_TYPE_BUY,
                                        response.getRequest().getCommodity(),
                                        response.getRequest().getRequestedLot(),
                                        quotesForClient.get(response.getRequest().getCommodity()).ask,
                                        userCommodities.get(response.getRequest().getCommodity()).getRatio()
                                );
                            }
                            break;
                            case TradeRequest.REQUEST_TYPE_SELL: {
                                position = new Position(
                                        UUID.randomUUID(), Position.ORDER_TYPE_SELL,
                                        response.getRequest().getCommodity(),
                                        response.getRequest().getRequestedLot(),
                                        quotesForClient.get(response.getRequest().getCommodity()).bid,
                                        userCommodities.get(response.getRequest().getCommodity()).getRatio()
                                );
                            }
                            break;
                        }

                        if(position != null){
                            positions.put(position.getOrderId(), position);
                            response.setResposneOk();
                        }

                    }catch(Exception ex){
                        Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, ex.getMessage(), ex);
                    }

                    if(null != position){

                        //update the current order state to db
                        PositionModel positionModel = new PositionModel();
                        positionModel.saveUpdatePosition(position);

                        //set client positions
                        String serializedPositions = gson.toJson(positions);
                        jedis.set(userPositionsKeyName, serializedPositions);
                    }

                    lock.release();

                }else{
                    Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, "Could not process user positions " + user.getUsername());
                }
            }catch(Exception ex){
                Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, "Could not process user positions " + user.getUsername());
            }
        }
    }


}
