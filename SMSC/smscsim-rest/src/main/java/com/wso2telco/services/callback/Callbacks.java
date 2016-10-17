package com.wso2telco.services.callback;

import static spark.Spark.post;
import static spark.SparkBase.port;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.Date;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wso2telco.db.DBConnectionPool;
import com.wso2telco.rest.json.request.deliverynotif.DeliveryInfoReceiveRequest;
import com.wso2telco.rest.json.request.sm.ErrorStat;
import com.wso2telco.rest.json.request.sm.SMSReceiveRequest;
import com.wso2telco.services.util.FileUtil;
import com.wso2telco.services.util.MessageSender;

public class Callbacks {
  private static final String MSG_TYPE_DELIVERY_NOTIF = "DELIVERY_NOTIF";
  private static final String MSG_TYPE_MO_SMS = "MO_SMS";
  
	public Callbacks() {
		port(Integer.parseInt(FileUtil.getPort()));
		post("/smscsim/callback/delivery", (req, res) -> {
			String reqBody = req.body().trim();
			reqBody = reqBody.replaceAll("\n", "").replaceAll("\r", "");
      	  	String reciverAddress = writeDeliveryNotifToDB(reqBody);
      	  	String dnNotifID = updateDeliveryNotifState( reciverAddress );
      	  	//EDITED
	      	JsonParser parser = new JsonParser();
	      	JsonObject jsonObject = parser.parse(reqBody).getAsJsonObject();
	      	String status="";
	      	status=jsonObject.get("deliveryInfoNotification").getAsJsonObject().get("deliveryInfo").getAsJsonObject().get("deliveryStatus").getAsString();
	      	String callback="";
	      	if ((!jsonObject.get("deliveryInfoNotification").getAsJsonObject().get("callbackData").toString().equals("null")) && jsonObject.get("deliveryInfoNotification").getAsJsonObject().get("callbackData")!=null) {
	      		callback=jsonObject.get("deliveryInfoNotification").getAsJsonObject().get("callbackData").getAsString();
			}
	      	String arr[]=getDNReciept(status,callback,reciverAddress);
	      	sendNotificationToSMSC(MSG_TYPE_DELIVERY_NOTIF, new String[]{ "DELIVERY_NOTIF", dnNotifID,arr[0],arr[1] });
      	  	//EDITED
      	  	res.status(200);
      	  	return "OK";
      	  	
		});

		post("/smscsim/callback/sm", (req, res) -> {
		  String reqBody = req.body().trim();
		  reqBody = reqBody.replaceAll("\n", "").replaceAll("\r", "");
		  String refNum = writeMoSMSToDB(reqBody);
		  sendNotificationToSMSC(MSG_TYPE_MO_SMS, new String[]{ "MO_SMS", refNum, null,null });
		  res.status(200);
		  return "OK";
		});

		post("/smsmessaging/v1/outbound/subscriptions", (req, res) -> {
		  String body = "{"
	        + "\"deliveryReceiptSubscription\": {"
	            + "\"callbackReference\": {"
	                + "\"callbackData\": \"12345\","
	                + "\"notifyURL\": \"http://localhost:8088/smscsim/callback/delivery\""
	            + "},"
	            + "\"senderAddresses\": ["
	                + "{"
	                    + "\"senderAddress\": \"7555\","
	                    + "\"operatorCode\": \"DIALOG\","
	                    + "\"filterCriteria\": \"123456\","
	                    + "\"status\": \"Created\""
	                + "}"
	            + "],"
	            + "\"resourceURL\": \"https://ideabiz.lk/apicall/smsmessaging/v1/outbound/subscriptions/1\","
	            + "\"clientCorrelator\": \"12345\""
	        + "}"
	      + "}";
		  res.type("application/json");
		  res.body(body);
		  res.status(200);
		  return body;
		});

		post("/smsmessaging/v1/smsmessaging/v1/inbound/subscriptions", (req, res) -> {
		  String body = "{"
          + "\"subscription\": {"
          + "\"callbackReference\": {"
            + "\"callbackData\": \"doSomething()\","
            + "\"notifyURL\": \"http://localhost:8088/smscsim/callback/sm\""
          + "},"
          + "\"destinationAddresses\": [{"
                  + "\"destinationAddress\": \"7555\","
                  + "\"operatorCode\": \"DIALOG\","
                  + "\"criteria\": \"Vote\","
                  + "\"status\": \"created\""
          + "}],"
          + "\"notificationFormat\": \"JSON\","
          + "\"clientCorrelator\": \"12345\","
          + "\"resourceURL\": \"http://www.example.com\""
        + "}"
      + "}";
		  res.type("application/json");
		  res.body(body);
		  res.status(200);
		  return body;
		});
	}

	private String writeMoSMSToDB(String reqBody) throws Exception {
		Connection connection = DBConnectionPool.getConnection();
	    String sql = "INSERT INTO mo_sms (body, ref_num, recieved_at) VALUES (?, ?, NOW())";
	    PreparedStatement stmt = connection.prepareStatement(sql);
	    String refNum = getSMSRefNo(reqBody);
	    stmt.setString(1, reqBody);
	    stmt.setString(2, refNum);
	    stmt.executeUpdate();
	    DBConnectionPool.releaseConnection(connection);
	    return refNum;
	}

	//============================================================DN RECIEPT
	
	private String getErrorStatus(String status) throws Exception{
	      Connection connection = DBConnectionPool.getConnection();
	      String sql = "SELECT ERROR_STATUS FROM status_data where state=? ORDER BY ID ASC LIMIT 1;";
	      PreparedStatement stmt = connection.prepareStatement( sql );
	      stmt.setString( 1, status);
	      System.out.println(stmt);
	      ResultSet objRes = stmt.executeQuery();
	      String errorKey = null;
	      while (objRes.next() ) {
	         errorKey = objRes.getString(1);
	      }
	   DBConnectionPool.releaseConnection(connection);
	      return errorKey;
	}

	private String getDeliveryStatus(String status) throws Exception{
	      Connection connection = DBConnectionPool.getConnection();
	      String sql = "SELECT DELIVER_STATUS FROM status_data where state=? ORDER BY ID ASC LIMIT 1;";
	      PreparedStatement stmt = connection.prepareStatement( sql );
	      stmt.setString( 1, status);
	      System.out.println(stmt);
	      ResultSet objRes = stmt.executeQuery();
	      String drKey = null;
	      while (objRes.next() ) {
	         drKey = objRes.getString(1);
	      }
	   DBConnectionPool.releaseConnection(connection);
	      return drKey;
	}
	private String[] getDNReciept(String status, String callback, String reciverAddress) throws Exception{
		
		String msg = "";
		String sme = "";
  		String messageId = "";
  		int sub = 0;//total segments
  		int dlvrd= 0;//total segments
  		String submitDate = "";
  		//String stat = "";
  		String err = "";
  		String shortMessage = "";
  		boolean recordExists=false;
		SimpleDateFormat output = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		
		Connection connection = DBConnectionPool.getConnection();
  		String sql2 = "select * from smse_req_resp_log where callback=? order by id desc limit 1;";
  		PreparedStatement stmt = connection.prepareStatement(sql2);
  		stmt.setString(1, callback);
  		ResultSet objRes = stmt.executeQuery();
  		if (objRes.next()) {
  			recordExists=true;
  			sme = objRes.getString("sme_id");
			messageId = objRes.getString("sms_id");
			int submittedCount=0;
			int tempVar=Integer.valueOf(objRes.getString("sar_total_segmants"));
			if (tempVar==0) {
				submittedCount=1;
			}else if (tempVar>=1) {
				submittedCount=tempVar;
			}
			sub=submittedCount;
			dlvrd=submittedCount;
			Date d = output.parse(objRes.getString("logged_at"));
			long mills = d.getTime();
			submitDate=formatDate(mills);
			shortMessage=objRes.getString("body");
  		
  		} else{
  			Connection connection2 = DBConnectionPool.getConnection();
  			String sql = "select * from smse_req_resp_log where address=? order by id desc limit 1";
  			PreparedStatement stmt2 = connection2.prepareStatement(sql);
  			stmt2.setString(1, reciverAddress.replace("tel", "").replace(":", "").replace("+", ""));
  			ResultSet objRes2 = stmt2.executeQuery();
  			
  			while(objRes2.next()){
  			recordExists=true;
  			sme = objRes2.getString("sme_id");
			messageId = objRes2.getString("sms_id");
			int submittedCount=0;
			int tempVar=Integer.valueOf(objRes2.getString("sar_total_segmants"));
			if (tempVar==0) {
				submittedCount=1;
			}else if (tempVar>=1) {
				submittedCount=tempVar;
			}
			sub=submittedCount;
			dlvrd=submittedCount;
			Date d = output.parse(objRes2.getString("logged_at"));
			long mills = d.getTime();
			submitDate=formatDate(mills);
			shortMessage=objRes2.getString("body");
  			}
			DBConnectionPool.releaseConnection(connection2);
  		}
  		
  		DBConnectionPool.releaseConnection(connection);
  		
		//"id:20000017 sub:1 dlvrd:1 submit date:1608030831 done date:1608030831 stat:DELIVRD err:0 text:Test"
		msg += "id:" + messageId + " ";
	    msg += "sub:" + sub + " ";
	    msg += "dlvrd:" + dlvrd + " ";
	    msg += "submit date:" + submitDate + " ";
	    msg += "done date:" + formatDate(System.currentTimeMillis()) + " ";
	    String drKey=getDeliveryStatus(status);
	    String errorKey=getErrorStatus(status);
	    if (drKey.equalsIgnoreCase("DELIVRD") && recordExists) {
	    	msg += "stat:" + drKey + " ";
	    	err=errorKey;
		}else {
			ErrorStat errorStat=new ErrorStat();
			errorStat=getErrorStat(status);
			msg += "stat:" + errorStat.getMsg() + " ";
	    	err=errorStat.getErr();
		}
	    msg += "err:" + err + " ";
	    int msgLen = shortMessage.length();
	    msg += "text:" + shortMessage.substring(0,(msgLen>20 ? 20:msgLen));
	    String []arr=new String[2];
	    	arr[0]=msg;
	    	arr[1]=sme;
	    return arr;
	}
	
	private ErrorStat getErrorStat(String status) throws Exception {
		String drKey=getDeliveryStatus(status);
		String errorKey=getErrorStatus(status);
		
		ErrorStat errorStat=new ErrorStat();
		if(drKey.equalsIgnoreCase("DELIVRD") ){
			drKey="UNKNOWN";
			errorKey="4";
		}
		errorStat.setMsg(drKey);
		errorStat.setErr(errorKey);
		return errorStat;
	}

    private String formatDate(long ms){
        synchronized (dateFormatter) {
            return dateFormatter.format(new Date(ms));
        }
    }
    private static final String DELIVERY_RCPT_DATE_FORMAT = "yyMMddHHmm";
    private SimpleDateFormat dateFormatter = new SimpleDateFormat(DELIVERY_RCPT_DATE_FORMAT);
	//============================================================DN RECIEPT
  private String writeDeliveryNotifToDB(String reqBody) throws Exception {
	  Connection connection = DBConnectionPool.getConnection();
	  String sql = "INSERT INTO delivery_notif (body, address, recieved_at) VALUES (?, ?, NOW())";
	  PreparedStatement stmt = connection.prepareStatement(sql);
	  String address = getAddress(reqBody);
	  stmt.setString(1, reqBody);
	  stmt.setString(2, address);
	  stmt.executeUpdate();
	  DBConnectionPool.releaseConnection(connection);
	  return address;
	}

  	private String updateDeliveryNotifState( String strRecipientAddress ) throws Exception {
  		Connection connection = DBConnectionPool.getConnection();
  		String sql = "SELECT * FROM delivery_notif WHERE address = ? AND delivered = 0 ORDER BY recieved_at ASC LIMIT 1";
  		PreparedStatement stmt = connection.prepareStatement( sql );
  		stmt.setString( 1, strRecipientAddress );
  		System.out.println(stmt);
  		ResultSet objRes = stmt.executeQuery();
  		String id = null;
		while ( objRes.next() ) {
  			id = objRes.getString(1);
  		}
  		DBConnectionPool.releaseConnection(connection);
  		return id;
  	}

	private String[] getDNRefNo(String reqBody) {
	  Gson gson = new Gson();
	  DeliveryInfoReceiveRequest deliveryReceiveReq = gson.fromJson(reqBody, DeliveryInfoReceiveRequest.class);
	  return deliveryReceiveReq.getDeliveryInfoNotification().getCallbackData().split(",");
	}

	private String getSMSRefNo(String reqBody) {
		Gson gson = new Gson();
	    SMSReceiveRequest smsReceiveReq = gson.fromJson(reqBody, SMSReceiveRequest.class);
	    return smsReceiveReq.getInboundSMSMessageNotification().getInboundSMSMessage().getMessageId();
	}
	
	private String getAddress(String reqBody) {
		Gson gson = new Gson();
		DeliveryInfoReceiveRequest deliveryReceiveReq = gson.fromJson(reqBody, DeliveryInfoReceiveRequest.class);
		return deliveryReceiveReq.getDeliveryInfoNotification().getDeliveryInfo().getAddress();
	}
	
	private void sendNotificationToSMSC(String msgType, String[] callbackDataArr) {
		MessageSender.sendNotificationToSMSC( callbackDataArr[0] + "," + callbackDataArr[1] + "," + callbackDataArr[2]+ "," + callbackDataArr[3]  );
	}

	public static void main(String[] args) {
		String settingsFile = "";
		if(args!=null && args.length>0) {
			settingsFile = args[0];
		}
		new FileUtil(settingsFile).start();
		new Callbacks();
	}
}
