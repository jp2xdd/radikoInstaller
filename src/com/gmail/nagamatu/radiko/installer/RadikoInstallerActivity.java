/*
 * Copyright (C) 2012 Tatsuo Nagamatsu <nagamatu@gmail.com>
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

package com.gmail.nagamatu.radiko.installer;

import org.apache.http.HttpException;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

public class RadikoInstallerActivity extends Activity {
    private static final String URL_LOGIN = "https://www.google.com/accounts/ClientLogin";
    private static final String URL_DOWNLOAD = "https://android.clients.google.com/market/api/ApiRequest";
    private static final String URL_GOOGLE_TALK_PROVIDER = "content://com.google.android.gsf.gservices";
    private static final String ACCOUNT_TYPE_HOSTED_OR_GOOGLE = "HOSTED_OR_GOOGLE";
    private static final String LOGIN_SERVICE = "androidsecure";
    private static final int PROTOCOL_VERSION = 2;
    private static final String UTF_8 = "UTF-8";
    
    private static final Pattern PATTERN_URL = Pattern.compile("https?:\\/\\/[^:]+");
    private static final Pattern PATTERN_MARKETDA = Pattern.compile("MarketDA.*?(\\d+)");

    private static final int DIALOG_SELECT_ACCOUNT = 0;
    private static final int DIALOG_PASSWD = 1;
    private static final int DIALOG_PROGRESS = 2;

    private static final int BUFSIZE = 4096;

    private static final String PARAMS_EMAIL = "Email";
    private static final String PARAMS_PASSWD = "Passwd";
    private static final String PARAMS_SERVICE = "service";
    private static final String PARAMS_ACCOUNTTYPE = "accountType";

    private static final String PACKAGE_NAME = "jp.radiko.Player";

    private final Map<String,String> mParams = new HashMap<String,String>();
    private String mDeviceId;
    private Account[] mAccounts;
    private Account mAccount;
    private String mPasswd;

    private final Map<String,String> mLoginInfo = new HashMap<String,String>();

    private static final Uri URI_GFS_SERVICE = Uri.parse(URL_GOOGLE_TALK_PROVIDER);

    private static String getDeviceId(Context context) {
      String id = null;
      Cursor c = context.getContentResolver().query(URI_GFS_SERVICE, null, null, new String[] {"android_id" }, null);
      try {
          c.moveToFirst();
          id = Long.toHexString(Long.parseLong(c.getString(1)));
      } catch (Exception e) {
          e.printStackTrace();
      } finally {
          if (c != null) {
              c.close();
          }
      }
      return id;
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        RadikoX509TrustManager.allowAllSSL();

        mDeviceId = getDeviceId(this);
        if (mDeviceId == null) {
            updateMessage(R.string.error_download, "Device ID not found");
            return;
        }

        updateMessage(R.string.check_account_passwd, null);
        getEmailAndPasswd();
    }

    private void updateMessage(final int id, final String error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView view = (TextView)findViewById(R.id.message);
                view.setText(id);
                if (id == R.string.error_download) {
                    final ProgressBar bar = (ProgressBar)findViewById(R.id.working);
                    bar.setVisibility(ProgressBar.INVISIBLE);
                    final TextView errormsg = (TextView)findViewById(R.id.error);
                    errormsg.setText(error);
                }
            }
        });
    }

    private void updateProgress(final int progress) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final ProgressBar view = (ProgressBar)findViewById(R.id.progress);
                view.setProgress(progress);
            }
        });
    }

    private void getEmailAndPasswd() {
        final AccountManager am = AccountManager.get(this);
        mAccounts = am.getAccountsByType("com.google");
        mAccount = null;

        switch (mAccounts.length) {
            case 0:
                updateMessage(R.string.error_download, getResources().getString(R.string.no_google_account));
                break;
            case 1:
                mAccount = mAccounts[0];
                showDialog(DIALOG_PASSWD);
                break;
            default:
                showDialog(DIALOG_SELECT_ACCOUNT);
                break;
        }
    }

    private Dialog onCreateSelectAccountDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.select_account);
        final CharSequence[] items = new CharSequence[mAccounts.length];
        int i = 0;
        for (Account a: mAccounts) {
            items[i++] = a.name;
        }
        builder.setItems(items, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mAccount = mAccounts[which];
                showDialog(DIALOG_PASSWD);
            }
        });
        builder.setCancelable(false);
        return builder.create();
    }

    private Dialog onCreatePasswdDialog() {
        final AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setPositiveButton(R.string.ok, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (dialog instanceof AlertDialog) {
                    final AlertDialog dlg = (AlertDialog)dialog;
                    final EditText passwd = (EditText)dlg.findViewById(R.id.dialog_text);
                    if (passwd == null) {
                        return;
                    }
                    mPasswd = passwd.getText().toString();
                    updateMessage(R.string.login_google, null);
                    new Thread(new Runnable() {
                        public void run() {
                            login();
                        }
                    }).start();
                }
            }        
        });
        ab.setNegativeButton(R.string.cancel, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                finish();
            }});
        ab.setTitle("Password for " + mAccount.name);
        final LayoutInflater inflator = getLayoutInflater();
        ab.setView(inflator.inflate(R.layout.passwd, null));
        return ab.create();
    }

    private Dialog onCreateProgressDialog() {
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMessage("Loading...");
        dialog.setCancelable(false);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_SELECT_ACCOUNT:
                return onCreateSelectAccountDialog();
            case DIALOG_PASSWD:
                return onCreatePasswdDialog();
            case DIALOG_PROGRESS:
                return onCreateProgressDialog();
        }
        return super.onCreateDialog(id);
    }

    private String getParamsStr() {
        mParams.put(PARAMS_EMAIL, mAccount.name);
        mParams.put(PARAMS_PASSWD, mPasswd);
        mParams.put(PARAMS_SERVICE, LOGIN_SERVICE);
        mParams.put(PARAMS_ACCOUNTTYPE, ACCOUNT_TYPE_HOSTED_OR_GOOGLE);

        try {
            final StringBuilder b = new StringBuilder();
            for (String key: mParams.keySet()) {
                if (b.length() != 0) {
                    b.append("&");
                }
                b.append(URLEncoder.encode(key, UTF_8));
                b.append("=");
                b.append(URLEncoder.encode(mParams.get(key), UTF_8));
            }
            return b.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static String streamToString(InputStream resultStream) throws IOException {
        final BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resultStream));
        final StringBuffer res = new StringBuffer();
        String aLine = reader.readLine();
        while (aLine != null) {
            res.append(aLine + "\n");
            aLine = reader.readLine();
        }
        return res.toString();
        
    }

    private void login() {
        try {
            final URL url = new URL(URL_LOGIN);
            final HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            try {
                con.setDoInput(true);
                con.setDoOutput(true);
                con.addRequestProperty("Content-type", "application/x-www-form-urlencoded");

                OutputStreamWriter writer = new OutputStreamWriter(con.getOutputStream(), UTF_8);
                writer.write(getParamsStr());
                writer.flush();
                writer.close();

                int errorCode = con.getResponseCode();
                if (errorCode >= 400) {
                    InputStream errorStream = con.getErrorStream();
                    try {
                        updateMessage(R.string.error_download, con.getResponseMessage());
                        final String errorData = streamToString(errorStream);
                        RadikoInstallerActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(RadikoInstallerActivity.this, errorData, Toast.LENGTH_SHORT).show();
                            }                            
                        });
                        throw new HttpException(errorData);
                    } finally {
                        errorStream.close();
                    }
                }

                InputStream in = con.getInputStream();
                try {
                    final DataInputStream din = new DataInputStream(in);
                    String line;
                    while ((line = din.readLine()) != null) {
                        mLoginInfo.clear();
                        final String ss[] = line.split("=");
                        mLoginInfo.put(ss[0], ss[1]);
                    }
                } finally {
                    in.close();
                }
                
                updateMessage(R.string.request_market, null);
                apiRequest();
            } catch (Exception e) {
                updateMessage(R.string.error_download, e.toString());
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            updateMessage(R.string.error_download, e.toString());
        }
    }

    @SuppressWarnings("unused")
    private static String readString(InputStream is) {
        try {
        final int len = readInt32(is);
        final byte[] data = new byte[len];
        is.read(data, 0, len);
        return new String(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static int readInt32(InputStream is) {
        int n = 0;
        try {
            int b = is.read();
            if (b <= 128) {
                return b;
            }
            return (b - 128) + readInt32(is) * 128;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return n;
    }

    private static void writeInt32(OutputStream os, int n) {
        try {
            for (int i = 0; i < 5 && n != 0; i++) {
                int b = n % 128;
                n >>= 7;
                if (n != 0) {
                    b += 128;
                }
                os.write(b);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeString(OutputStream os, String s) {
        try {
            byte[] buf = s.getBytes();
            writeInt32(os, buf.length);
            os.write(buf, 0, buf.length);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private byte[] createRequest() {
        try {
            String packageName = PACKAGE_NAME;
            int baseLen;
    
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            os.write(10);
            writeString(os, mLoginInfo.get("Auth"));
            os.write(16);
            os.write(1);
            os.write(24);
            writeInt32(os, 2009011);
            os.write(34);
            writeString(os, mDeviceId);
            os.write(42);
            writeString(os, "passion:9");
            os.write(50);
            writeString(os, "en");
            os.write(58);
            writeString(os, "us");
            os.write(66);
            writeString(os, "DoCoMo");
            os.write(74);
            writeString(os, "DoCoMo");
            os.write(82);
            writeString(os, "44010");
            os.write(90);
            writeString(os, "44010");
            baseLen = os.size() + 1 - 3;    // reduce three bytes - one marker (10) plus 2 bytes for baseLen
            os.write(19);
            os.write(82);
            writeInt32(os, packageName.length() + 2);
            os.write(10);
            writeString(os, packageName);
            os.write(20);
            os.flush();
            os.close();
    
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.write(10);
            writeInt32(request, baseLen + 2);
            request.write(os.toByteArray(), 0, os.size());
            return request.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void apiRequest() {
        try {
            final URL url = new URL(URL_DOWNLOAD);
            final HttpsURLConnection con = (HttpsURLConnection)url.openConnection();
            try {
                con.setDoInput(true);
                con.setDoOutput(true);
                con.addRequestProperty("Content-type", "application/x-www-form-urlencoded");
                con.addRequestProperty("User-Agent", "Android-Market/2");
                con.addRequestProperty("Cookie", "ANDROIDSECURE=" + mLoginInfo.get("Auth"));

                String request64 = Base64.encodeToString(createRequest(), Base64.URL_SAFE);
                String requestData = "version=" + PROTOCOL_VERSION + "&request="+request64;

                OutputStreamWriter writer = new OutputStreamWriter(con.getOutputStream(), UTF_8);
                writer.write(requestData);
                writer.flush();
                writer.close();

                int errorCode = con.getResponseCode();
                if (errorCode >= 400) {
                    InputStream errorStream = con.getErrorStream();
                    try {
                        updateMessage(R.string.error_download, con.getResponseMessage());
                        final String errorData = streamToString(errorStream);
                        RadikoInstallerActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(RadikoInstallerActivity.this, errorData, Toast.LENGTH_SHORT).show();
                            }                            
                        });
                        throw new HttpException(errorData);
                    } finally {
                        errorStream.close();
                    }
                }

                String downloadUrl = null;
                String marketDa = null;
                final InputStream in = con.getInputStream();
                final GZIPInputStream zis = new GZIPInputStream(new BufferedInputStream(in));

                // Response is in ProtBuf format. But use a easy way to extract string here
                final String response = streamToString(zis);
                Matcher m = PATTERN_URL.matcher(response);
                if (m.find()) {
                    downloadUrl = m.group();
                }

                m = PATTERN_MARKETDA.matcher(response);
                if (m.find()) {
                    marketDa = m.group(1);
                }
                if (downloadUrl == null || marketDa == null) {
                    updateMessage(R.string.error_download, "Missing URL or MarketDA in response");
                    return;
                }
                updateMessage(R.string.download_package, null);
                download(downloadUrl, marketDa);
            } catch (Exception e) {
                updateMessage(R.string.error_download, e.toString());
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            updateMessage(R.string.error_download, e.toString());
        }
    }

    private void download(String urlstr, String marketDa) {
        try {
            final URL url = new URL(urlstr);

            final HttpsURLConnection con = (HttpsURLConnection)url.openConnection();
            try {
                con.setDoInput(true);
                con.setDoOutput(false);
                con.addRequestProperty("User-Agent", "Android-Market/2");
                con.addRequestProperty("Cookie", "MarketDA=" + marketDa);
                con.connect();
 
                int errorCode = con.getResponseCode();
                if (errorCode >= 400) {
                    updateMessage(R.string.error_download, con.getResponseMessage());
                    InputStream errorStream = con.getErrorStream();
                    try {
                        final String errorData = streamToString(errorStream);
                        RadikoInstallerActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(RadikoInstallerActivity.this, errorData, Toast.LENGTH_SHORT).show();
                            }              
                        });
                        throw new HttpException(errorData);
                    } finally {
                        errorStream.close();
                    }
                }
                
                if (errorCode == 302) {
                    download2(con.getHeaderField("Location"), marketDa);
                    return;
                }
                updateMessage(R.string.error_download, "Wrong response code for download");
            } catch (Exception e) {
                updateMessage(R.string.error_download, e.toString());
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            updateMessage(R.string.error_download, e.toString());
        }
    }
    
    private void download2(String urlstr, String marketDa) {
        try {
            final URL url = new URL(urlstr);

            final HttpURLConnection con = (HttpURLConnection)url.openConnection();
            try {
                con.setDoInput(true);
                con.setDoOutput(false);
                con.addRequestProperty("User-Agent", "Android-Market/2");
                con.addRequestProperty("Cookie", "MarketDA=" + marketDa);
                con.connect();
 
                int errorCode = con.getResponseCode();
                if (errorCode >= 400) {
                    updateMessage(R.string.error_download, con.getResponseMessage());
                    InputStream errorStream = con.getErrorStream();
                    try {
                        final String errorData = streamToString(errorStream);
                        RadikoInstallerActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(RadikoInstallerActivity.this, errorData, Toast.LENGTH_SHORT).show();
                            }                            
                        });
                        throw new HttpException(errorData);
                    } finally {
                        errorStream.close();
                    }
                }

                final File file = new File(Environment.getExternalStorageDirectory(), PACKAGE_NAME + ".apk");
                if (file.exists()) {
                    file.delete();
                }
                final FileOutputStream out = new FileOutputStream(file);
                InputStream in = con.getInputStream();
                int total = con.getContentLength();
                int len = total;
                try {
                    final byte[] buf = new byte[BUFSIZE];
                    while (len > 0) {
                        int rsz = in.read(buf);
                        if (rsz < 0) {
                            break;
                        }
                        out.write(buf, 0, rsz);
                        len -= rsz;
                        updateProgress(100 * (total - len) / total);
                    }
                    if (len != 0) {
                        updateMessage(R.string.error_download, "Insufficient Response");
                        return;
                    }

                    updateMessage(R.string.install_package, null);
                    final Intent intent = new Intent(Intent.ACTION_VIEW); 
                    intent.setDataAndType(Uri.fromFile(file),  "application/vnd.android.package-archive"); 
                    startActivity(intent);
                    finish();
                } finally {
                    in.close();
                    out.flush();
                    out.close();
                }
            } catch (Exception e) {
                updateMessage(R.string.error_download, e.toString());
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            updateMessage(R.string.error_download, e.toString());
        }
    }
}
