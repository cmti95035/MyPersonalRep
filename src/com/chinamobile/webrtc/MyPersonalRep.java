package com.chinamobile.webrtc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.att.android.speech.ATTSpeechError;
import com.att.android.speech.ATTSpeechError.ErrorType;
import com.att.android.speech.ATTSpeechAudioLevelListener;
import com.att.android.speech.ATTSpeechErrorListener;
import com.att.android.speech.ATTSpeechResult;
import com.att.android.speech.ATTSpeechResultListener;
import com.att.android.speech.ATTSpeechService;
import com.att.android.speech.ATTSpeechStateListener;
import com.chinamobile.customerservice.server.CustomerServiceContext;
import com.chinamobile.customerservice.server.CustomerServiceRequestBuilders;
import com.linkedin.r2.transport.common.Client;
import com.linkedin.r2.transport.common.bridge.client.TransportClientAdapter;
import com.linkedin.r2.transport.http.client.HttpClientFactory;
import com.linkedin.restli.client.Response;
import com.linkedin.restli.client.ResponseFuture;
import com.linkedin.restli.client.RestClient;
import com.linkedin.restli.common.EmptyRecord;
import com.linkedin.restli.common.IdResponse;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.TextView;

public class MyPersonalRep extends Activity {
    private Button speakButton = null;
    private String text = ""; // last Speech to Text result
    private TextView resultView = null;
    private WebView webView = null;
    private String oauthToken = null;
    private static final String USER_AGENT = "Mozilla/5.0";
    private static String WEBRTC_URL = "https://apprtc.appspot.com";
    
    /**
     * way to find the phone number from an android phone
     * @return
     */
    public String getMyPhoneNumber()
    {
        return ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                .getLine1Number();
    }

    /**
     * Call to the server to get a chat room number to access
     * the apprtc.appspot.com
     * @return
     */
	private String findChatRoomFromServer() {
		String room = "";
		try {

			final HttpClientFactory http = new HttpClientFactory();
			final Client r2Client = new TransportClientAdapter(
					http.getClient(Collections.<String, String> emptyMap()));

			// Create a RestClient to talk to customer service server
			RestClient restClient = new RestClient(r2Client,
					"http://54.67.77.140:8080/CustomerService-server/");
			CustomerServiceRequestBuilders builders = new CustomerServiceRequestBuilders();

			ResponseFuture<String> actionFuture = restClient
					.sendRequest(builders.actionFindChatRoom().build());
			Thread.sleep(1500);
			Response<String> actionResp = actionFuture.getResponse();
			int statusCode = actionResp.getStatus();
			if (statusCode != 200) {
				Log.e("MyPersonalRep", "status code: " + statusCode);
			}
			room = actionResp.getEntity();
		} catch (Exception ex) {
			Log.d("MyPersonalRep", ex.getMessage());
			for(StackTraceElement elem : ex.getStackTrace())
			{
				Log.d("MyPersonalRep", elem.toString());
			}
		}

		return room;
	}
	
	/**
	 * Use a HttpURLConnection to get a room number directly from 
	 * apprtc.appspot.com
	 * @return
	 */
	private String findChatRoomNumber() {
		String retValue = null;
		try {
			
			URL obj = new URL(WEBRTC_URL);
			HttpURLConnection con = (HttpURLConnection) obj.openConnection();

			// optional default is GET
			con.setRequestMethod("GET");

			// add request header
			con.setRequestProperty("User-Agent", USER_AGENT);

			BufferedReader in = new BufferedReader(new InputStreamReader(
					con.getInputStream()));
			String inputLine;

			Pattern pattern = Pattern.compile(WEBRTC_URL + "/r/\\d+");
			Matcher matcher = null;
			while ((inputLine = in.readLine()) != null) {
				Log.d("findChatRoomNumber", inputLine);
				matcher = pattern.matcher(inputLine);
				if (matcher.find()) {
					String[] parts = matcher.group().toString().split("/");
					Log.d("MyPersonalRep", "Found a match: "
							+ parts[parts.length - 1]);
					retValue = parts[parts.length - 1];
				}
			}
			in.close();
		} catch (Exception ex) {
			for(StackTraceElement elem : ex.getStackTrace())
			{
				Log.d("MyPersonalRep", elem.toString());
			}
			Log.d("MyPersonalRep", "found exception: " + ex.getMessage());
			
		}
		return retValue;
	}
	
    /** 
     * Called when the activity is first created.  This is where we'll hook up 
     * our views in XML layout files to our application.
    **/
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // First, we specify which layout resource we'll be using.
        setContentView(R.layout.speech);
        
        // This is the Speak button that the user presses to start a speech
        // interaction.
        speakButton = (Button)findViewById(R.id.speak_button);
        speakButton.setOnClickListener(speechButtonListener);
        
        // This will show the recognized text.
        resultView = (TextView)findViewById(R.id.result);
        
        // This will show a website receiving the recognized text.
        webView = (WebView)findViewById(R.id.webview);
//        configureWebView();
    }
    
    /** 
     * Called when the activity is coming to the foreground.
     * This is where we will fetch a fresh OAuth token.
    **/
    @Override
    protected void onStart() {
        super.onStart();
        
        // Set the ATTSpeechService parameters.
        setupSpeechService();
        // Fetch the OAuth credentials.  
        validateOAuth();
    }
    
    /** Convenience routine to get speech service. **/
    private ATTSpeechService getSpeechService()
    {
        return ATTSpeechService.getSpeechService(this);
    }
    
    /** 
     * Perform the action of the "Push to Talk" button.
     * Starts the SpeechKit service that listens to the microphone and returns
     * the recognized text.
    **/
    private void setupSpeechService() {
        // The ATTSpeechKit uses a singleton object to interface with the 
        // speech server.
        ATTSpeechService speechService = getSpeechService();
        
        // Register for the success and error callbacks.
        speechService.setSpeechResultListener(speechResultListener);
        speechService.setSpeechErrorListener(speechErrorListener);
        
        // Register for callbacks to update our custom UI.
        speechService.setShowUI(false);
        speechService.setSpeechStateListener(speechStateListener);
        speechService.setAudioLevelListener(speechLevelListener);
        
        // Next, we'll put in some basic parameters.
        // First is the Request URL.  This is the URL of the speech recognition 
        // service that you were given during onboarding.
        try {
            speechService.setRecognitionURL(new URI(SpeechConfig.serviceUrl()));
        }
        catch (URISyntaxException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /**
     * This callback object will get the speech status change notifications.
    **/
    private final SpeechStateListener speechStateListener = new SpeechStateListener();
    private class SpeechStateListener implements ATTSpeechStateListener {
        @Override public void onStateChanged(SpeechState newSpeechState) {
            switch (newSpeechState) {
            case RECORDING:
                // Speech SDK has started capturing audio from the microphone.
                resultView.setText(R.string.status_listening);
                // Change the button behavior to manually endpoint audio.
                speakButton.setText(R.string.button_stop);
                speakButton.setOnClickListener(stopButtonListener);
                break;
            case PROCESSING:
                // Speech SDK is done capturing audio and is now waiting for the server.
                resultView.setText(R.string.status_processing);
                // Change the button behavior to cancel waiting.
                speakButton.setText(R.string.button_cancel);
                speakButton.setOnClickListener(cancelButtonListener);
                break;
            default:
                // Speech SDK is not interacting with the user.
                // Restore the button behavior to start listening.
                speakButton.setText(R.string.button_speak);
                speakButton.setOnClickListener(speechButtonListener);
                // Make sure the Speech to Text result appears instead of 
                // status message.
                resultView.setText(text);
                break;
            }
        }
    }
    
    /**
     * Handle a press on the "Push to Talk" button.  
    **/
    private final SpeechButtonListener speechButtonListener = new SpeechButtonListener();
    private class SpeechButtonListener implements View.OnClickListener {
        @Override public void onClick(View v) {
            // Re-access the singleton ATTSpeechService for this activity.
            ATTSpeechService speechService = getSpeechService();
            
            // Set the OAuth token that was fetched in the background.
            speechService.setBearerAuthToken(oauthToken);
            
            // Add extra arguments for speech recognition.
            // The parameter is the name of the current screen within this app.
            speechService.setXArgs(
                    Collections.singletonMap("ClientScreen", "main"));

            // Finally we have all the information needed to start the speech service.  
            speechService.startListening();
            Log.d("MyPersonalRep", "Starting speech interaction");
        }
    };
    
    /**
     * Handle a press on the "Stop" button.  
    **/
    private final StopButtonListener stopButtonListener = new StopButtonListener();
    private class StopButtonListener implements View.OnClickListener {
        @Override public void onClick(View v) {
            // Re-access the singleton ATTSpeechService for this activity.
            ATTSpeechService speechService = getSpeechService();
            // End microphone input and wait for the server to process the audio.
            speechService.stopListening();
        }
    };
    
    /**
     * Handle a press on the "Cancel" button.  
    **/
    private final CancelButtonListener cancelButtonListener = new CancelButtonListener();
    private class CancelButtonListener implements View.OnClickListener {
        @Override public void onClick(View v) {
            // Re-access the singleton ATTSpeechService for this activity.
            ATTSpeechService speechService = getSpeechService();
            // Stop waiting for the server to process audio.
            speechService.cancel();
            // The speech service won't make any more callbacks, so restore the 
            // UI to normal.
            speechStateListener.onStateChanged(ATTSpeechStateListener.SpeechState.IDLE);
        }
    };
    
    /**
     * This callback object will get all the speech audio level notifications.
    **/
    private final SpeechLevelListener speechLevelListener = new SpeechLevelListener();
    private class SpeechLevelListener implements ATTSpeechAudioLevelListener {
        @Override public void onAudioLevel(int level) {
            // Show the audio level.
            resultView.setText(getString(R.string.status_audio, level));
        }
        
    }
    
    /**
     * This callback object will get all the speech success notifications.
    **/
    private final SpeechResultListener speechResultListener = new SpeechResultListener();
    private class SpeechResultListener implements ATTSpeechResultListener {
        public void onResult(ATTSpeechResult result) {
            // The hypothetical recognition matches are returned as a list of strings.
            List<String> textList = result.getTextStrings();
            String resultText = null;
            if (textList != null && textList.size() > 0) {
                // There may be multiple results, but this example will only use
                // the first one, which is the most likely.
                resultText = textList.get(0);
            }
            if (resultText != null && resultText.length() > 0) {
                // This is where your app will process the recognized text.
                Log.v("MyPersonalRep", "Recognized "+textList.size()+" hypotheses.");
                handleRecognition(resultText);
            }
            else {
                // The speech service did not recognize what was spoken.
                Log.v("MyPersonalRep", "Recognized no hypotheses.");
                alert("Didn't recognize speech", "Please try again.");
            }
        }
    }
    
    /** Make use of the recognition text in this app. **/
    private void handleRecognition(String resultText) {
        // In this example, we set display the text in the result view.
        text = resultText;
        resultView.setText(resultText);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
//        webView.setWebViewClient(new WebViewClient() 
        webView.setWebChromeClient(new WebChromeClient()
         {

            @SuppressLint("NewApi") 
            //@Override
            public void onPermissionRequest(final PermissionRequest request) {
                Log.d("MyPersonalRep", "onPermissionRequest");
                runOnUiThread(new Runnable() {
                	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public void run() {
                		request.grant(request.getResources());
                    }
                });
            }

        });
        try{
            String roomNum = AccessToS3.getObjectFromS3(); //findChatRoomFromServer();
            Log.d("MyPersonalRep", "found room: " + roomNum);
            sendDataToServer(roomNum, resultText);
            if(roomNum != null && !roomNum.isEmpty())
            	webView.loadUrl("https://apprtc.appspot.com/room/" + roomNum);
            else
            	webView.loadUrl("https://apprtc.appspot.com/");        	
        }catch(Exception ex)
        {
        	Log.d("MyPersonalRep", "exception: " + ex.getMessage());
        }
    }
    
	private void sendDataToServer(String roomNum, String issue) {
		try {

			final HttpClientFactory http = new HttpClientFactory();
			final Client r2Client = new TransportClientAdapter(
					http.getClient(Collections.<String, String> emptyMap()));

			// Create a RestClient to talk to customer service server
			RestClient restClient = new RestClient(r2Client,
					"http://54.67.77.140:8080/CustomerService-server/");
			CustomerServiceRequestBuilders builders = new CustomerServiceRequestBuilders();
			String phone = getMyPhoneNumber();
			phone = phone == null ? "" : phone;
			Log.d("MyPersonalRep", "phone: " + phone);
			CustomerServiceContext context = new CustomerServiceContext()
					.setChatRoomId(roomNum!=null ? roomNum : "").setPhone(phone).setIssue(issue);
			ResponseFuture<IdResponse<Long>> createFuture = restClient
					.sendRequest(builders.create().input(context).build());
			Thread.sleep(1500);
			int statusCode = createFuture.getResponse().getStatus();
			if (statusCode != 201) {
				Log.e("MyPersonalRep", "status code: " + statusCode);
			}
		} catch (Exception ex) {
			Log.d("MyPersonalRep", ex.getMessage());
			for(StackTraceElement elem : ex.getStackTrace())
			{
				Log.d("MyPersonalRep", elem.toString());
			}
		}
	}
	
    /** Configure the webview that displays websites with the recognition text. **/
    private void configureWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // Let the webview display the URL
            }
        });
    }

    /**
     * This callback object will get all the speech error notifications.
    **/
    private final SpeechErrorListener speechErrorListener = new SpeechErrorListener();
    private class SpeechErrorListener implements ATTSpeechErrorListener {
        public void onError(ATTSpeechError error) {
            ErrorType resultCode = error.getType();
            if (resultCode == ErrorType.USER_CANCELED) {
                // The user canceled the speech interaction.
                // This can happen through several mechanisms:
                // pressing a cancel button in the speech UI;
                // pressing the back button; starting another activity;
                // or locking the screen.
                
                // In all these situations, the user was instrumental
                // in canceling, so there is no need to put up a UI alerting 
                // the user to the fact.
                Log.v("MyPersonalRep", "User canceled.");
            }
            else {
                // Any other value for the result code means an error has occurred.
                // The argument includes a message to help the programmer 
                // diagnose the issue.
                String errorMessage = error.getMessage();
                Log.v("MyPersonalRep", "Recognition error #"+resultCode+": "+errorMessage);
                
                alert("Speech Error", "Please try again later.");
            }
        }
    }

    /**
     * Start an asynchronous OAuth credential check. 
     * Disables the Speak button until the check is complete.
    **/
    private void validateOAuth() {
        SpeechAuth auth = 
            SpeechAuth.forService(SpeechConfig.oauthUrl(), SpeechConfig.oauthScope(), 
                SpeechConfig.oauthKey(), SpeechConfig.oauthSecret());
        auth.fetchTo(new OAuthResponseListener());
        speakButton.setText(R.string.button_wait);
        speakButton.setEnabled(false);
    }
    
    /**
     * Handle the result of an asynchronous OAuth check.
    **/
    private class OAuthResponseListener implements SpeechAuth.Client {
        public void 
        handleResponse(String token, Exception error)
        {
            if (token != null) {
                oauthToken = token;
                speakButton.setText(R.string.button_speak);
                speakButton.setEnabled(true);
            }
            else {
                Log.v("MyPersonalRep", "OAuth error: "+error);
                // There was either a network error or authentication error.
                // Show alert for the latter.
                alert("Speech Unavailable", 
                    "This app was rejected by the speech service.  Contact the developer for an update.");
            }
        }
    }

    private void alert(String header, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
            .setTitle(header)
            .setCancelable(true)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
        AlertDialog alert = builder.create();
        alert.show();
    }
}
