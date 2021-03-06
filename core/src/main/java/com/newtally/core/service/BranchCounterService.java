package com.newtally.core.service;

import com.blockcypher.utils.gson.GsonFactory;
import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.newtally.core.ServiceFactory;
import com.newtally.core.dto.CounterDto;
import com.newtally.core.dto.CurrencyDiscountDto;
import com.newtally.core.model.CoinType;
import com.newtally.core.model.Currency;
import com.newtally.core.model.Device;
import com.newtally.core.model.Merchant;
import com.newtally.core.model.MerchantBranch;
import com.newtally.core.model.MerchantConfiguration;
import com.newtally.core.model.MerchantCounter;
import com.newtally.core.model.Order;
import com.newtally.core.model.OrderStatus;
import com.newtally.core.model.Role;
import com.newtally.core.resource.ThreadContext;
import com.newtally.core.util.CollectionUtil;

import javax.annotation.security.RolesAllowed;
import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BranchCounterService extends AbstractService implements IAuthenticator {
    

    public BranchCounterService(EntityManager em, ThreadContext ctx) {
        super(em, ctx);
    }

    @RolesAllowed(Role.BRANCH_COUNTER)
    public String handlePayment(CoinType type, double amount) {
        return null;
    }

    @RolesAllowed({Role.BRANCH_COUNTER})
    public MerchantConfiguration getConfiguration() {
        return null;
    }

    public boolean authenticate(String username, String password) {
        Query query = em.createNativeQuery("SELECT  count(*) FROM branch_counter " +
                "WHERE password = :password AND active = true");

        query.setParameter("password", password);

        BigInteger count = (BigInteger) query.getSingleResult();

        return count.intValue() == 1;
    }
    
    public String getUserId(String username, String password) {

        return password;
    }
    
    public MerchantCounter getCurrentCounter() {
        return getCountersOfWhereClause("WHERE password = :counterCode",
                CollectionUtil.getSingleEntryMap("counterCode", ctx.getCurrentCounterCode())).get(0);
    }
    
    List<MerchantCounter> getCountersOfWhereClause(
            String whereClause, Map<String, Object> params) {
        Query query = em.createNativeQuery("SELECT  id, branch_id, password, phone, email, active, name " +
                "FROM branch_counter " + whereClause);

        setParams(params, query);

        List rs = query.getResultList();

        List<MerchantCounter> counters = new ArrayList<>();
        for(Object ele : rs) {
            Object [] fields = (Object[]) ele;

            MerchantCounter counter = new MerchantCounter();
            counter.setId(((BigInteger) fields[0]).longValue());
            counter.setBranchId( ((BigInteger) fields[1]).longValue());
            counter.setPassword((String) fields[2]); 
            counter.setPhone((String) fields[3]);
            counter.setEmail((String) fields[4]);
            counter.setActive((Boolean) fields[5]);
            counter.setName((String) fields[6]);
            
            counters.add(counter);
        }

        return counters;
    }
    public CounterDto getCounterDetails(){
        CounterDto counterDto=new CounterDto();
        MerchantCounter counter=getCurrentCounter();
        MerchantBranch branch=ServiceFactory.getInstance().getMerchantService()
                .getBranchesOfWhereClause("WHERE id = :branchId",
                CollectionUtil.getSingleEntryMap("branchId", counter.getBranchId())).get(0);
        Merchant merchant=ServiceFactory.getInstance().getMerchantService().getMerchantById(branch.getMerchantId());
        counterDto.setAddress(branch.getAddress());
        counterDto.setBranch_name(branch.getName());
        counterDto.setCounter_id(counter.getId());
        counterDto.setCounter_name(counter.getName());
        counterDto.setMerchant_name(merchant.getName());
        counterDto.setMerchant_id(merchant.getId());
        counterDto.setEmail(counter.getEmail());
         return counterDto;       
    }

    public List<CurrencyDiscountDto> getCurrencyDiscounts(Double paymentAmount) throws Exception {
		Double discount = null;
		Query query = em.createNativeQuery("SELECT  id, code, name FROM currency where active=true");

        List rs = query.getResultList();
        List<Currency> currencies = new ArrayList<>();
        List<CurrencyDiscountDto> currencyDiscountDtos=new ArrayList<>();
        CounterDto counter=getCounterDetails();
        for(Object ele : rs) {
            Object [] fields = (Object[]) ele;
            Currency currency = new Currency();
            
            CurrencyDiscountDto currencyDiscountDto=new CurrencyDiscountDto();
            currency.setId(((Integer) fields[0]).longValue());
            Query queryForDiscount = em.createNativeQuery("SELECT percentage FROM discount where merchant_id =:merchantId and currency_id =:currencyId") ;
            
            queryForDiscount.setParameter("merchantId", counter.getMerchant_id());
            queryForDiscount.setParameter("currencyId", currency.getId());
            if(!queryForDiscount.getResultList().isEmpty()){
               discount = (Double)queryForDiscount.getSingleResult();
            }
            currency.setCode(CoinType.valueOf((String) fields[1])); 
            currency.setName((String) fields[2]);
            currencyDiscountDto.setCurrency_id(currency.getId());
            currencyDiscountDto.setCurrency_code(currency.getCode().toString());
            currencyDiscountDto.setCurrency_name(currency.getName());
            Double payableAmount=null;
            if(discount !=null && discount>0) {
            currencyDiscountDto.setDiscount(discount);
            currencyDiscountDto.setDiscount_amount(paymentAmount*currencyDiscountDto.getDiscount()/100);
            }
            payableAmount=paymentAmount-currencyDiscountDto.getDiscount_amount();
            Double coinAmount=0d;
            if(getBitCoinCostInINR()>0) {
            coinAmount=BigDecimal.valueOf(payableAmount/getBitCoinCostInINR()).setScale(8, RoundingMode.HALF_DOWN).doubleValue();
            }
            currencyDiscountDto.setCurrency_amount(coinAmount);
            currencyDiscountDtos.add(currencyDiscountDto);
            currencies.add(currency);
        }
        return currencyDiscountDtos;
    }

    public List<Order> getOrders(HashMap<String, Object> input) {
        Query query = em.createNativeQuery("SELECT  id, currency_amount, discount_amount,currency_id, "+
                                            "currency_code, status, created_date, payment_amount FROM order_invoice where counter_id=:counter_id order by created_date desc");
        query.setParameter("counter_id", input.get("counter_id"));
        List rs = query.getResultList();
        List<Order> orders=new ArrayList<>();
        for(Object ele : rs) {
            Object [] fields = (Object[]) ele;
            Order order=new Order();
            order.setId(((BigInteger) fields[0]).longValue());
            order.setCurrencyAmount((Double)fields[1]);
            order.setDiscountAmount((Double)fields[2]);
            order.setCurrencyId((Integer)fields[3]);
            order.setCurrencyCode((String)fields[4]);
            order.setStatus(OrderStatus.valueOf((String)fields[5]));
            order.setCreatedDate((Date)fields[6]);
            order.setPaymentAmount((Double)fields[7]);
            orders.add(order);
        }
        return orders;
    }

    public CounterDto getCounterById(long id) {
        CounterDto counterDto=new CounterDto();
        MerchantCounter counter=getCountersOfWhereClause("WHERE id = :id",
                CollectionUtil.getSingleEntryMap("id", id)).get(0);
        MerchantBranch branch=ServiceFactory.getInstance().getMerchantService()
                .getBranchesOfWhereClause("WHERE id = :branchId",
                CollectionUtil.getSingleEntryMap("branchId", counter.getBranchId())).get(0);
        Merchant merchant=ServiceFactory.getInstance().getMerchantService().getMerchantById(branch.getMerchantId());
        counterDto.setAddress(branch.getAddress());
        counterDto.setBranch_name(branch.getName());
        counterDto.setCounter_id(counter.getId());
        counterDto.setCounter_name("Counter #"+counter.getId());
        counterDto.setMerchant_name(merchant.getName());
         return counterDto;  
    }

    public List<Order> getAllOrders() {
        Query query = em.createNativeQuery("SELECT  id, currency_amount, discount_amount,currency_id, "+
                "currency_code, status, created_date, payment_amount FROM order_invoice where counter_id=:counter_id order by created_date desc");
            query.setParameter("counter_id", getCurrentCounter().getId());
            List rs = query.getResultList();
            List<Order> orders=new ArrayList<>();
            for(Object ele : rs) {
            Object [] fields = (Object[]) ele;
            Order order=new Order();
            order.setId(((BigInteger) fields[0]).longValue());
            order.setCurrencyAmount((Double)fields[1]);
            order.setDiscountAmount((Double)fields[2]);
            order.setCurrencyId((Integer)fields[3]);
            order.setCurrencyCode((String)fields[4]);
            order.setStatus(OrderStatus.valueOf((String)fields[5]));
            order.setCreatedDate((Date)fields[6]);
            order.setPaymentAmount((Double)fields[7]);
            orders.add(order);
            }
           return orders;
    }

    public Double getBitCoinCostInINR() throws Exception {
        

        String url = "https://api.coindesk.com/v1/bpi/currentprice/INR.json";

        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();

        con.setRequestMethod("GET"); 
        con.setRequestProperty("authority", "api.coindesk.com");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");

        int responseCode = con.getResponseCode();
        if(responseCode == 200) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));

        LinkedTreeMap<String, Object> input = gson.fromJson(in, LinkedTreeMap.class);
        LinkedTreeMap<String, Object> bpi=(LinkedTreeMap<String, Object>) input.get("bpi");
        LinkedTreeMap<String, Object> inr=(LinkedTreeMap<String, Object>) bpi.get("INR");
        
        return (Double) inr.get("rate_float");
        }
        return 0d;

    }
    public String getBranchIdByCounterPwd(String counterPassword){
        Query query = em.createNativeQuery("select branch_id from branch_counter where password=:counterPassword");
        query.setParameter("counterPassword", counterPassword);
        return query.getResultList().get(0).toString();
    }

    public String getBranchIdByCounterId(int counterId){
        Query query = em.createNativeQuery("select branch_id from branch_counter where id=:id");
        query.setParameter("id", counterId);
        return query.getResultList().get(0).toString();
    }

    public Device saveDevice(Device device) {
        
        EntityTransaction trn = em.getTransaction();
        trn.begin();
        try {
            System.out.println("user_id:::"+device.getUserId());
        Query queryToCheck = em.createNativeQuery("select id from devices where user_id=:user_id"); 
        queryToCheck.setParameter("user_id", device.getUserId());
        List rs= queryToCheck.getResultList();
        if(rs.isEmpty()) {
            System.out.println("counter_id:::"+device.getUserId());
            Query query = em.createNativeQuery("INSERT INTO devices ( " +
                    "deviceid, device_type, registration_key, user_id, created_date) " +
                    "VALUES( :deviceid, :device_type, :registration_key, :user_id, :created_date)");
    
            query.setParameter("deviceid", device.getDeviceId());
            query.setParameter("device_type", device.getDeviceType());
            query.setParameter("registration_key", device.getRegistrationKey());
            query.setParameter("user_id", device.getUserId());
            query.setParameter("created_date", new Date());
            
            query.executeUpdate();
            trn.commit();
        } else {
            System.out.println("id:::"+(Integer) rs.get(0));
            Query query = em.createNativeQuery("update devices set registration_key=:registration_key, deviceid=:deviceid, device_type=:device_type, user_id=:user_id, modified_date=:modified_date " +
                    " where id=:id");
            
            query.setParameter("registration_key", device.getRegistrationKey());
            query.setParameter("deviceid", device.getDeviceId());
            query.setParameter("device_type", device.getDeviceType());
            query.setParameter("user_id", device.getUserId());
            query.setParameter("modified_date", new Date());
            query.setParameter("id", (Integer) rs.get(0));
            query.executeUpdate();
            trn.commit();
        }
        return device;

    } catch (Exception e) {
        trn.rollback();
        throw e;
    }
    }

	public Map<String, Object> getMerchantIdAndBranchNoByCounterPwd(String counterPassword) {
		Map<String, Object> params = new HashMap<>();
		Query query = em.createNativeQuery("select mb.merchant_id, mb.branch_no from merchant_branch mb join "
				+ "branch_counter bc on mb.id = bc.branch_id and bc.password=:counterPassword");
		query.setParameter("counterPassword", counterPassword);
		Object[] obj = (Object[]) query.getResultList().get(0);
		params.put(MerchantBranch.MERCHANT_ID, obj[0]);
		params.put(MerchantBranch.BRANCH_NO, obj[1]);
		return params;
	}
	
	@Override
    public boolean checkEmail(String email) {
        Query query = em.createNativeQuery("select email from branch_counter where email=:email");
        query.setParameter("email", email);
        List rs=query.getResultList();
        if(rs.isEmpty())
            return false;
        else
            return true;
    }
	
	 @Override
	    public void resetPassword(String email, String password) {
	        EntityTransaction trn = em.getTransaction();
	        trn.begin();
	        try {
	            Query query = em.createNativeQuery("UPDATE branch_counter SET password = :password " +
	                    "WHERE email = :email");

	            query.setParameter("password", password);
	            query.setParameter("email", email);
	            query.executeUpdate();

	            trn.commit();

	        } catch (Exception e) {
	            trn.rollback();
	            throw e;
	        }
	    }
}
