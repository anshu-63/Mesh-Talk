package com.exa.meshtalk;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    // Layout containers for toggles
    private android.view.View layoutConnectionSetup;
    private android.view.View layoutChatScreen;
    private android.widget.LinearLayout messageContainer;
    private android.widget.ScrollView chatScrollView;

    // Inputs
    private android.widget.EditText messageInput;
    private android.widget.ImageButton btnSend;

    // Track the active peer endpoint ID globally
    private String connectedEndpointId = null;



    private static final String TAG = "MeshTalk";
    private static final String SERVICE_ID = "com.exa.meshtalk.SERVICE_ID";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private ConnectionsClient connectionsClient;

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            Log.d(TAG, "Connection initiated with " + connectionInfo.getEndpointName());
            if (connectionsClient != null) {
                connectionsClient.acceptConnection(endpointId, payloadCallback);
            }
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Log.d(TAG, "Connected successfully to " + endpointId);
                connectedEndpointId=endpointId;
                runOnUiThread(()->{
                    if(layoutConnectionSetup !=null && layoutChatScreen!=null){
                        layoutConnectionSetup.setVisibility(View.GONE);
                        layoutChatScreen.setVisibility(View.VISIBLE);
                    }
                });
                Payload payload = Payload.fromBytes("Hello!".getBytes(StandardCharsets.UTF_8));
                if (connectionsClient != null) {
                    connectionsClient.sendPayload(endpointId, payload);
                }
            } else {
                Log.e(TAG, "Connection failed: " + result.getStatus().getStatusMessage());
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.d(TAG, "Disconnected from " + endpointId);
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES && payload.asBytes() != null) {
                String message = new String(payload.asBytes(), StandardCharsets.UTF_8);
                Log.d(TAG, "Received Message: " + message);
                // Fix: UI operations like Toasts must run on the UI thread
                runOnUiThread(() -> addMessageToUi(message, false));
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        try {
            connectionsClient = Nearby.getConnectionsClient(this);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Nearby Connections client", e);
        }


        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        android.widget.Button advertiser = findViewById(R.id.advertiser);
       android.widget.Button discoverer = findViewById(R.id.discoverer);
        // Initialize layout containers
        layoutConnectionSetup = findViewById(R.id.layoutConnectionSetup);
        layoutChatScreen = findViewById(R.id.layoutChatScreen);
        messageContainer = findViewById(R.id.messageContainer);
        chatScrollView = findViewById(R.id.chatScrollView);

        // Initialize inputs
        messageInput = findViewById(R.id.messageInput);
        btnSend = findViewById(R.id.btnSend);

        // Set click listener for send button
        btnSend.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                sendMessage();
            }
        });


        advertiser.setOnClickListener(v -> {
            if (hasPermissions()) {
                startAdvertising();
            } else {
                requestPermissions();
            }
        });

        discoverer.setOnClickListener(v -> {
            if (hasPermissions()) {
                startDiscovering();
            } else {
                requestPermissions();
            }
        });

        if (!hasPermissions()) {
            requestPermissions();
        } else {
            ensureHardwareEnabled();
        }

    }

    private void startAdvertising() {
        if (connectionsClient == null) return;
        AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        try {
            connectionsClient.startAdvertising(Build.MODEL, SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
                    .addOnSuccessListener(unused -> {
                        Log.d(TAG, "Advertising started...");
                        Toast.makeText(this, "Advertising started", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Advertising failed", e);
                        Toast.makeText(this, "Advertising failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permissions for advertising", e);
            requestPermissions();
        }
    }

    private void startDiscovering() {
        if (connectionsClient == null) return;
        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        try {
            connectionsClient.startDiscovery(
                    SERVICE_ID, new EndpointDiscoveryCallback() {
                        @Override
                        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo discoveredEndpointInfo) {
                            Log.d(TAG, "Endpoint found: " + endpointId);
                            if (connectionsClient != null) {
                                connectionsClient.requestConnection(Build.MODEL, endpointId, connectionLifecycleCallback)
                                        .addOnFailureListener(e -> Log.e(TAG, "Request connection failed", e));
                            }
                        }

                        @Override
                        public void onEndpointLost(@NonNull String endpointId) {
                            Log.d(TAG, "Lost track of device: " + endpointId);
                        }
                    }, discoveryOptions)
                    .addOnSuccessListener(unused -> {
                        Log.d(TAG, "Discovery started...");
                        Toast.makeText(this, "Discovery started", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Discovery failed", e);
                        Toast.makeText(this, "Discovery failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permissions for discovery", e);
            requestPermissions();
        }
    }

    private boolean hasPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE);
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        // Location permissions are mandatory for scanning/advertising
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        // Android 12+ (API 31) Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        // Android 13+ (API 33) Nearby Wi-Fi and Notification permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && hasPermissions()) {
            ensureHardwareEnabled();
        } else if (requestCode == PERMISSION_REQUEST_CODE) {
            Toast.makeText(this, "All permissions are required for MeshTalk to function properly.", Toast.LENGTH_LONG).show();
        }
    }

    private void ensureHardwareEnabled() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                try {
                    startActivity(intent);
                } catch (SecurityException e) {
                    Log.e(TAG, "Unable to request Bluetooth enable", e);
                }
            }
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                startActivity(intent);
            } else {
                try {
                    wifiManager.setWifiEnabled(true);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to enable WiFi", e);
                }
            }
        }
    }
    private void sendMessage() {
        String msg = messageInput.getText().toString().trim();
        if (msg.isEmpty() || connectedEndpointId == null) {
            return;
        }

        // Convert your text string into a Google Nearby byte payload
        com.google.android.gms.nearby.connection.Payload payload =
                com.google.android.gms.nearby.connection.Payload.fromBytes(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Send it through the airwaves to the connected device!
        connectionsClient.sendPayload(connectedEndpointId, payload);

        // Add to your local screen layout as a right-aligned bubble (true = Me)
        addMessageToUi(msg, true);

        // Clear the typing field so you can type the next message
        messageInput.setText("");
    }

    private void addMessageToUi(String message, boolean isMe) {
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(message);
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        textView.setPadding(32, 24, 32, 24); // Give the bubbles some clean internal spacing

        // Configure layout params for the message block
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 12;
        params.bottomMargin = 12;

        if (isMe) {
            // Right-aligned bubble for sent messages
            params.gravity = android.view.Gravity.END;
            textView.setBackgroundResource(android.R.drawable.editbox_background);
            textView.setTextColor(android.graphics.Color.BLACK);
        } else {
            // Left-aligned bubble for received messages
            params.gravity = android.view.Gravity.START;
            textView.setBackgroundResource(android.R.drawable.toast_frame);
            textView.setTextColor(android.graphics.Color.WHITE);
        }

        textView.setLayoutParams(params);

        // Force the layout change onto the Main UI Thread safely
        runOnUiThread(() -> {
            messageContainer.addView(textView);
            // Smoothly auto-scroll down to see new messages instantly
            chatScrollView.post(() -> chatScrollView.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }
}
