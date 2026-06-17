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
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MeshTalk";
    private static final String SERVICE_ID = "mesh.talk.id";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // View Layers
    private View layoutPageOnboarding;
    private View layoutPageDeviceList;
    private View layoutPageChatDashboard;

    // Stage 1 Components
    private android.widget.EditText usernameInput;
    public android.widget.TextView txtSavedUsernameDisplay;

    // Stage 2 Components
    private android.widget.ListView devicesListView;
    private ArrayList<String> discoveredNodeNamesList = new ArrayList<>();
    private android.widget.ArrayAdapter<String> listAdapter;

    // Stage 3 Components
    private android.widget.TextView txtChatRoomHeader;
    private android.widget.LinearLayout messageContainer;
    private android.widget.ScrollView chatScrollView;
    private android.widget.EditText messageInput;
    private android.widget.ImageButton btnSend;

    // States and Network Maps
    private String myCustomUsername = "";
    private String activeTargetRecipient = "";
    private android.content.SharedPreferences sharedPreferences;
    private ConnectionsClient connectionsClient;

    // Lookups for routing tables
    private HashMap<String, String> connectedNeighborsMap = new HashMap<>(); // Maps Username -> EndpointID
    private HashMap<String, String> endpointToUsernameMap = new HashMap<>(); // Maps EndpointID -> Username

    // --- NETWORK CALLBACKS ---

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            Log.d(TAG, "Node spotted: " + info.getEndpointName());
            if (connectionsClient != null) {
                connectionsClient.requestConnection(myCustomUsername, endpointId, connectionLifecycleCallback)
                        .addOnFailureListener(e -> Log.e(TAG, "Auto-connect failed: " + e.getMessage()));
            }
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Log.d(TAG, "Node went out of physical scanning range.");
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            Log.d(TAG, "Handshake initiated with: " + info.getEndpointName());

            // FIX 1: Capture the real username string on initial connection request receipt!
            endpointToUsernameMap.put(endpointId, info.getEndpointName());

            if (connectionsClient != null) {
                connectionsClient.acceptConnection(endpointId, payloadCallback);
            }
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Log.d(TAG, "Mesh tunnel link established with endpoint: " + endpointId);

                String humanReadableName = endpointToUsernameMap.containsKey(endpointId) ?
                        endpointToUsernameMap.get(endpointId) : endpointId;

                // FIX 2: Map the network tables using the custom user name key token string
                connectedNeighborsMap.put(humanReadableName, endpointId);

                runOnUiThread(() -> {
                    if (!discoveredNodeNamesList.contains(humanReadableName)) {
                        discoveredNodeNamesList.add(humanReadableName);
                        if (listAdapter != null) {
                            listAdapter.notifyDataSetChanged();
                        }
                    }
                });
            } else {
                Log.e(TAG, "Handshake failed: " + result.getStatus().getStatusMessage());
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.d(TAG, "Node disconnected from mesh.");
            String humanReadableName = endpointToUsernameMap.get(endpointId);
            if (humanReadableName == null) {
                humanReadableName = endpointId;
            }
            connectedNeighborsMap.remove(humanReadableName);
            endpointToUsernameMap.remove(endpointId);
            String finalName = humanReadableName;
            runOnUiThread(() -> {
                discoveredNodeNamesList.remove(finalName);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            });
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES && payload.asBytes() != null) {
                try {
                    String rawJson = new String(payload.asBytes(), StandardCharsets.UTF_8);
                    org.json.JSONObject packet = new org.json.JSONObject(rawJson);

                    String type = packet.optString("packetType", "CHAT_MESSAGE");
                    String sender = packet.getString("senderName");
                    String receiver = packet.getString("receiverName");
                    String messageText = packet.getString("messageText");

                    // FIX 3: Detect and catch screen sync command signals instantly
                    if (type.equals("SWITCH_TO_CHAT")) {
                        runOnUiThread(() -> {
                            activeTargetRecipient = sender;
                            layoutPageDeviceList.setVisibility(View.GONE);
                            layoutPageChatDashboard.setVisibility(View.VISIBLE);
                            txtChatRoomHeader.setText("Chatting with: " + activeTargetRecipient);
                        });
                        return;
                    }

                    // FIX 4: Normalize text matching rules for rendering text messages cleanly
                    if (receiver.equalsIgnoreCase(myCustomUsername) || receiver.equalsIgnoreCase(endpointId)) {
                        addMessageToUi("[" + sender + "] " + messageText, false);
                    } else {
                        Log.d(TAG, "Relaying packet safely from " + sender + " to " + receiver);
                        if (connectionsClient != null) {
                            if (connectedNeighborsMap.containsKey(receiver)) {
                                String directRouteId = connectedNeighborsMap.get(receiver);
                                connectionsClient.sendPayload(directRouteId, payload);
                            } else {
                                for (String neighborId : connectedNeighborsMap.values()) {
                                    if (!neighborId.equals(endpointId)) {
                                        connectionsClient.sendPayload(neighborId, payload);
                                    }
                                }
                            }
                        }
                    }
                } catch (org.json.JSONException e) {
                    Log.e(TAG, "Packet read parse crash: " + e.getMessage());
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
            // Track packet progress blocks if necessary
        }
    };

    // --- ACTIVITY LIFECYCLE ---

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

        // Apply Window and Keyboard Padding dynamically
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets imeAndBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(imeAndBars.left, imeAndBars.top, imeAndBars.right, imeAndBars.bottom);
            return insets;
        });

        layoutPageOnboarding = findViewById(R.id.layoutPageOnboarding);
        layoutPageDeviceList = findViewById(R.id.layoutPageDeviceList);
        layoutPageChatDashboard = findViewById(R.id.layoutPageChatDashboard);

        usernameInput = findViewById(R.id.usernameInput);
        txtSavedUsernameDisplay = findViewById(R.id.txtSavedUsernameDisplay);
        android.widget.Button btnStartChatting = findViewById(R.id.btnStartChatting);

        devicesListView = findViewById(R.id.devicesListView);
        listAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredNodeNamesList);
        devicesListView.setAdapter(listAdapter);

        txtChatRoomHeader = findViewById(R.id.txtChatRoomHeader);
        messageContainer = findViewById(R.id.messageContainer);
        chatScrollView = findViewById(R.id.chatScrollView);
        messageInput = findViewById(R.id.messageInput);
        btnSend = findViewById(R.id.btnSend);

        // Preference naming updated to completely refresh local storage keys
        sharedPreferences = getSharedPreferences("MeshTalkPrefsV3", MODE_PRIVATE);
        if (sharedPreferences.contains("saved_username")) {
            myCustomUsername = sharedPreferences.getString("saved_username", "");
            usernameInput.setVisibility(View.GONE);
            txtSavedUsernameDisplay.setText("Welcome back: " + myCustomUsername);
            txtSavedUsernameDisplay.setVisibility(View.VISIBLE);
        }

        btnStartChatting.setOnClickListener(v -> {
            if (myCustomUsername.isEmpty()) {
                String input = usernameInput.getText().toString().trim();
                if (input.isEmpty()) {
                    usernameInput.setError("Username cannot be empty!");
                    return;
                }
                myCustomUsername = input;
                sharedPreferences.edit().putString("saved_username", myCustomUsername).apply();
            }

            layoutPageOnboarding.setVisibility(View.GONE);
            layoutPageDeviceList.setVisibility(View.VISIBLE);

            if (hasPermissions()) {
                startSimultaneousMeshRadio();
            } else {
                requestPermissions();
            }
        });

        devicesListView.setOnItemClickListener((parent, view, position, id) -> {
            activeTargetRecipient = discoveredNodeNamesList.get(position);

            layoutPageDeviceList.setVisibility(android.view.View.GONE);
            layoutPageChatDashboard.setVisibility(android.view.View.VISIBLE);
            txtChatRoomHeader.setText("Chatting with: " + activeTargetRecipient);

            try {
                org.json.JSONObject syncPacket = new org.json.JSONObject();
                syncPacket.put("packetType", "SWITCH_TO_CHAT");
                syncPacket.put("senderName", myCustomUsername);
                syncPacket.put("receiverName", activeTargetRecipient);
                syncPacket.put("messageText", "SYNC");

                String rawJson = syncPacket.toString();
                Payload payload = Payload.fromBytes(rawJson.getBytes(StandardCharsets.UTF_8));

                if (connectedNeighborsMap.containsKey(activeTargetRecipient)) {
                    connectionsClient.sendPayload(connectedNeighborsMap.get(activeTargetRecipient), payload);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send sync screen command", e);
            }
        });

        btnSend.setOnClickListener(v -> sendMessageToMeshPacket());

        if (!hasPermissions()) {
            requestPermissions();
        } else {
            ensureHardwareEnabled();
        }
    }

    // --- MESH LOGIC DRIVERS ---

    private void startSimultaneousMeshRadio() {
        if (connectionsClient == null) return;

        AdvertisingOptions adOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        DiscoveryOptions discOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();

        try {
            connectionsClient.startAdvertising(myCustomUsername, SERVICE_ID, connectionLifecycleCallback, adOptions)
                    .addOnSuccessListener(unused -> Log.d(TAG, "Simultaneous Advertising online..."))
                    .addOnFailureListener(e -> Log.e(TAG, "Advertising launch failed", e));

            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                    .addOnSuccessListener(unused -> Log.d(TAG, "Simultaneous Discovery online..."))
                    .addOnFailureListener(e -> Log.e(TAG, "Discovery launch failed", e));
        } catch (SecurityException e) {
            Log.e(TAG, "Security hardware error: ", e);
        }
    }

    private void sendMessageToMeshPacket() {
        String msg = messageInput.getText().toString().trim();
        if (msg.isEmpty() || activeTargetRecipient.isEmpty()) return;

        try {
            org.json.JSONObject packet = new org.json.JSONObject();
            packet.put("packetType", "CHAT_MESSAGE");
            packet.put("senderName", myCustomUsername);
            packet.put("receiverName", activeTargetRecipient);
            packet.put("messageText", msg);

            String rawJson = packet.toString();
            Payload payload = Payload.fromBytes(rawJson.getBytes(StandardCharsets.UTF_8));

            if (connectionsClient != null) {
                if (connectedNeighborsMap.containsKey(activeTargetRecipient)) {
                    String directRouteId = connectedNeighborsMap.get(activeTargetRecipient);
                    connectionsClient.sendPayload(directRouteId, payload);
                } else {
                    for (String neighborEndpointId : connectedNeighborsMap.values()) {
                        connectionsClient.sendPayload(neighborEndpointId, payload);
                    }
                }
            }

            addMessageToUi("[" + myCustomUsername + "] " + msg, true);
            messageInput.setText("");

        } catch (org.json.JSONException e) {
            Log.e(TAG, "Packet packing logic failed", e);
        }
    }

    private void addMessageToUi(String message, boolean isMe) {
        android.widget.TextView textView = new android.widget.TextView(this);
        textView.setText(message);
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        textView.setPadding(32, 24, 32, 24);

        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 12;
        params.bottomMargin = 12;

        if (isMe) {
            params.gravity = android.view.Gravity.END;
            textView.setBackgroundResource(android.R.drawable.editbox_background);
            textView.setTextColor(android.graphics.Color.BLACK);
        } else {
            params.gravity = android.view.Gravity.START;
            textView.setBackgroundResource(android.R.drawable.toast_frame);
            textView.setTextColor(android.graphics.Color.WHITE);
        }

        textView.setLayoutParams(params);

        runOnUiThread(() -> {
            messageContainer.addView(textView);
            chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // --- PERMISSIONS AND HARDWARE HANDSHAKES ---

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
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

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
            Toast.makeText(this, "Permissions are mandatory for node mesh clustering.", Toast.LENGTH_LONG).show();
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
                    Log.e(TAG, "Failed to switch WiFi on", e);
                }
            }
        }
    }
}