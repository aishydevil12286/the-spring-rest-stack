package com.jl.crm.android;

import android.app.Activity;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.util.Log;
import com.jl.crm.android.widget.CrmOAuthFlowWebView;
import com.jl.crm.client.CrmConnectionFactory;
import com.jl.crm.client.CrmOperations;
import org.springframework.social.connect.Connection;
import org.springframework.social.connect.sqlite.SQLiteConnectionRepository;
import org.springframework.social.connect.sqlite.support.SQLiteConnectionRepositoryHelper;
import org.springframework.social.oauth2.*;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * A central place to lookup information about the currrent state of the connection with the CRM.
 *
 * @author Josh Long
 */
public class CrmConnectionState {

    private volatile boolean started;
    private OAuth2Operations oAuth2Operations;
    private String oauthAccessTokenCallbackUri;
    private SQLiteConnectionRepository sqLiteConnectionRepository;
    private CrmConnectionFactory connectionFactory;
    private SQLiteConnectionRepositoryHelper repositoryHelper;
    private Activity activity;

    public CrmConnectionState(Activity context,
                              CrmConnectionFactory connectionFactory,
                              SQLiteConnectionRepositoryHelper repositoryHelper,
                              SQLiteConnectionRepository sqLiteConnectionRepository,
                              String oauthAccessTokenCallbackUri) {
        this.activity = context;
        this.repositoryHelper = repositoryHelper;
        this.sqLiteConnectionRepository = sqLiteConnectionRepository;
        this.connectionFactory = connectionFactory;
        this.oAuth2Operations = connectionFactory.getOAuthOperations();
        this.oauthAccessTokenCallbackUri = oauthAccessTokenCallbackUri;
    }

    public boolean isStarted() {
        return this.started;
    }

    public CrmOAuthFlowWebView webView() {

        CrmOAuthFlowWebView.AccessTokenReceivedListener accessTokenReceivedListener =
                new CrmOAuthFlowWebView.AccessTokenReceivedListener() {
                    @Override
                    public void accessTokenReceived(final String accessToken) {
                        try {
                            AsyncTask<?, ?, Connection<CrmOperations>> asyncTask = new AsyncTask<Object, Object, Connection<CrmOperations>>() {
                                @Override
                                protected Connection<CrmOperations> doInBackground(Object... params) {
                                    Connection<CrmOperations> crmOperationsConnection = installAccessToken(accessToken);
                                    return crmOperationsConnection;
                                }
                            };

                            asyncTask.execute(new Object[0]);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }

                    }
                };
        String authenticateUri = buildAuthenticationUrl();
        String returnUri = activity.getString(R.string.oauth_access_token_callback_uri);
        return new CrmOAuthFlowWebView(this.activity, authenticateUri, returnUri, accessTokenReceivedListener);
    }

    public void start(final Runnable yes, final Runnable no) {
        if (isStarted()) // NB: no need to start the engine again if its already running.
            return;
        // todo extract this out to a configuration variable
        //resetLocalConnections();

        new AsyncTask<Object, Object, Connection<CrmOperations>>() {
            @Override
            protected Connection<CrmOperations> doInBackground(Object... params) {
                Connection<CrmOperations> connection = sqLiteConnectionRepository.findPrimaryConnection(CrmOperations.class);
                boolean connected = false;
                try {
                    if (connection != null && connection.test()) {
                        connected = true;
                    }
                } catch (Throwable t) {
                    // something goes wrong, its never set to true, we run the reconnect logic
                    Log.e(CrmConnectionState.class.getName(),
                            "error when trying to ascertain an existing connection.", t);
                }

                activity.runOnUiThread(connected ? startNotifyingRunnableWrapper(yes) : startNotifyingRunnableWrapper(no));
                return null;
            }
        }.execute();
    }

    public void resetLocalConnections() {

        SQLiteDatabase sqLiteDatabase = null;
        try {
            sqLiteDatabase = repositoryHelper.getWritableDatabase();
            clearAllConnections();
        } finally {
            if (null != sqLiteDatabase) {
                sqLiteDatabase.close();
            }
        }
    }

    private void clearAllConnections() {
        MultiValueMap<String, Connection<?>> mvMapOfConnections =
                sqLiteConnectionRepository.findAllConnections();
        for (String k : mvMapOfConnections.keySet()) {
            List<Connection<?>> connectionList = mvMapOfConnections.get(k);
            for (Connection<?> c : connectionList) {
                sqLiteConnectionRepository.removeConnection(c.getKey());
            }
        }
    }

    private Runnable startNotifyingRunnableWrapper(final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                r.run();
                started = true;
            }
        };
    }

    public String buildAuthenticationUrl() {
        OAuth2Template oAuth2Template = (OAuth2Template) oAuth2Operations;
        oAuth2Template.setUseParametersForClientAuthentication(false);
        OAuth2Parameters oAuth2Parameters = new OAuth2Parameters();
        oAuth2Parameters.setScope("read,write");
        if (StringUtils.hasText(oauthAccessTokenCallbackUri)) {
            oAuth2Parameters.setRedirectUri(oauthAccessTokenCallbackUri);
        }
        return oAuth2Operations.buildAuthenticateUrl(GrantType.IMPLICIT_GRANT, oAuth2Parameters);
    }

    public Connection<CrmOperations> installAccessToken(String accessToken) {
        AccessGrant accessGrant = new AccessGrant(accessToken);
        resetLocalConnections();
        Connection<CrmOperations> crmOperationsConnection = connectionFactory.createConnection(accessGrant);
        sqLiteConnectionRepository.addConnection(crmOperationsConnection);
        return crmOperationsConnection;
    }

}
