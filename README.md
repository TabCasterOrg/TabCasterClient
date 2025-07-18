# StreamClient3 - Android UDP Streaming Client

An open-source Android application for ultra-low latency screen streaming over UDP. The client automatically discovers streaming servers on the local network and provides a seamless fullscreen streaming experience.

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Dependencies](#dependencies)
- [Installation](#installation)
- [Usage](#usage)
- [API Documentation](#api-documentation)
- [Network Protocol](#network-protocol)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [License](#license)

## Overview

StreamClient3 is designed for ultra-low latency screen streaming applications. It uses UDP for minimal network overhead and implements aggressive buffering strategies to achieve near real-time performance. The application automatically discovers compatible streaming servers using mDNS/DNS-SD and provides a simple interface for connection and playback.

## Features

- **Ultra-low latency streaming** (50-100ms buffer)
- **Automatic server discovery** via mDNS/DNS-SD
- **Manual server connection** via IP:port
- **Automatic fullscreen mode** during streaming
- **Landscape orientation** for optimal viewing
- **Connection retry logic** with timeout handling
- **Clean disconnect handling**

## Architecture

The application consists of three main components:

1. **MainActivity**: UI controller and coordination
2. **NetworkDiscovery**: mDNS server discovery
3. **StreamPlayer**: UDP streaming and media playback

## Dependencies

Add these dependencies to your `app/build.gradle`:

```gradle
dependencies {
    implementation 'androidx.media3:media3-exoplayer:1.2.0'
    implementation 'androidx.media3:media3-ui:1.2.0'
    implementation 'androidx.media3:media3-datasource:1.2.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

## Installation

1. Clone the repository
2. Open in Android Studio
3. Add the required dependencies
4. Add network permissions to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

5. Build and run the application

## Usage

### Basic Usage

1. **Launch the app** - It will automatically start discovering servers
2. **Connect to a server**:
   - Tap "Find Servers" to see discovered servers
   - Or enter IP:port manually and tap "Connect"
3. **Stream automatically enters fullscreen landscape mode**
4. **Press back button to disconnect**

### Manual Connection

Enter server address in one of these formats:
- `192.168.1.100:5001` (IP and port)
- `5001` (port only, assumes localhost)
- `192.168.1.100` (IP only, assumes port 5001)

---

# API Documentation

## MainActivity Class

Main activity that coordinates the streaming application.

### Constructor

```kotlin
class MainActivity : AppCompatActivity(), 
    NetworkDiscovery.DiscoveryCallback,
    StreamPlayer.StreamCallback
```

**Description**: Main activity implementing discovery and stream callbacks.

### Key Methods

#### `onCreate(savedInstanceState: Bundle?)`

**Input**: 
- `savedInstanceState: Bundle?` - Saved instance state

**Output**: `Unit`

**Description**: Initializes the activity, sets up UI components, and starts server discovery.

**Example**:
```kotlin
// Called automatically by Android framework
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Initialization code
}
```

#### `handleConnection()`

**Input**: None

**Output**: `Unit`

**Description**: Handles connection logic based on manual input or discovered servers.

**Internal Logic**:
1. Checks if manual IP:port is entered
2. If yes, connects directly
3. If no, shows server selection dialog

#### `connect(serverAddress: String)`

**Input**:
- `serverAddress: String` - Server address in format "ServerName@IP:port" or "IP:port"

**Output**: `Unit`

**Description**: Initiates connection to specified server.

**Example**:
```kotlin
connect("MyServer@192.168.1.100:5001")
connect("192.168.1.100:5001")
```

#### `enterFullscreen()` / `exitFullscreen()`

**Input**: None

**Output**: `Unit`

**Description**: Toggles fullscreen landscape mode for optimal streaming experience.

**Features**:
- Hides system UI and action bar
- Forces landscape orientation
- Hides control layout

### Callback Methods

#### `onServerFound(serverAddress: String)`

**Input**:
- `serverAddress: String` - Discovered server in format "ServerName@IP:port"

**Output**: `Unit`

**Description**: Called when NetworkDiscovery finds a new server.

#### `onStreamReady()`

**Input**: None

**Output**: `Unit`

**Description**: Called when stream is ready for playback. Automatically enters fullscreen mode.

---

## NetworkDiscovery Class

Handles automatic discovery of streaming servers using mDNS/DNS-SD.

### Constructor

```kotlin
NetworkDiscovery(
    private val context: Context,
    private val callback: DiscoveryCallback
)
```

**Inputs**:
- `context: Context` - Android context
- `callback: DiscoveryCallback` - Callback for discovery events

### Interface: DiscoveryCallback

```kotlin
interface DiscoveryCallback {
    fun onServerFound(serverAddress: String)
    fun onServerLost(serverName: String)
}
```

### Methods

#### `start()`

**Input**: None

**Output**: `Unit`

**Description**: Starts mDNS service discovery for `_screenstream._tcp.` services.

**Internal Process**:
1. Initializes NsdManager
2. Creates discovery listener
3. Starts service discovery
4. Resolves found services to get IP:port

**Example**:
```kotlin
val discovery = NetworkDiscovery(context, callback)
discovery.start()
```

#### `stop()`

**Input**: None

**Output**: `Unit`

**Description**: Stops discovery and clears discovered servers list.

#### `getServers(): List<String>`

**Input**: None

**Output**: `List<String>` - List of discovered servers

**Description**: Returns current list of discovered servers.

**Example**:
```kotlin
val servers = discovery.getServers()
// Returns: ["MyServer@192.168.1.100:5001", "OtherServer@192.168.1.101:5001"]
```

### Service Discovery Protocol

**Service Type**: `_screenstream._tcp.`

**Service Name Format**: Excludes services containing "AndroidStreamClient"

**Resolved Format**: `"ServiceName@IP:port"`

---

## StreamPlayer Class

Handles UDP streaming and media playback with ultra-low latency configuration.

### Constructor

```kotlin
StreamPlayer(private val context: Context)
```

**Input**:
- `context: Context` - Android context

### Interface: StreamCallback

```kotlin
interface StreamCallback {
    fun onStreamReady()
    fun onStreamError(error: String)
    fun onStreamStateChanged(isPlaying: Boolean)
}
```

### Methods

#### `setCallback(callback: StreamCallback)`

**Input**:
- `callback: StreamCallback` - Callback for stream events

**Output**: `Unit`

**Description**: Sets callback for stream events.

#### `initializePlayerView(): PlayerView`

**Input**: None

**Output**: `PlayerView` - Configured ExoPlayer PlayerView

**Description**: Creates and configures PlayerView with optimized settings.

**Configuration**:
- Disables unnecessary controls (next, previous, fast forward, rewind)
- Enables screen wake lock
- Optimizes for streaming content

#### `connect(serverAddress: String)`

**Input**:
- `serverAddress: String` - Server address in various formats

**Output**: `Unit`

**Description**: Establishes connection to streaming server and starts playback.

**Process**:
1. Parses server address
2. Sends connection message via UDP
3. Waits for server acknowledgment
4. Starts UDP streaming on port+1

**Example**:
```kotlin
streamPlayer.connect("MyServer@192.168.1.100:5001")
```

#### `sendConnectionMessage(serverIp: String, port: Int): Boolean`

**Input**:
- `serverIp: String` - Server IP address
- `port: Int` - Server control port

**Output**: `Boolean` - Success status

**Description**: Sends connection handshake message to server.

**Message Format**: `"CONNECT:width:height:framerate"`

**Example Message**: `"CONNECT:1920:1080:30"`

**Process**:
1. Creates UDP socket
2. Sends connection message
3. Waits for "ACK" or "SERVER_READY" response
4. Retries up to MAX_RETRIES times

#### `parseServerAddress(serverAddress: String): Pair<String, Int>`

**Input**:
- `serverAddress: String` - Server address in various formats

**Output**: `Pair<String, Int>` - (IP address, port)

**Description**: Parses different server address formats.

**Supported Formats**:
- `"ServerName@192.168.1.100:5001"` → `("192.168.1.100", 5001)`
- `"192.168.1.100:5001"` → `("192.168.1.100", 5001)`
- `"5001"` → `("127.0.0.1", 5001)`
- `"192.168.1.100"` → `("192.168.1.100", 5001)`

#### `release()`

**Input**: None

**Output**: `Unit`

**Description**: Releases ExoPlayer resources and cleans up.

### Ultra-Low Latency Configuration

The StreamPlayer uses aggressive settings for minimal latency:

```kotlin
// Buffer settings (milliseconds)
private const val MIN_BUFFER_MS = 50           // Minimum buffer
private const val MAX_BUFFER_MS = 100          // Maximum buffer  
private const val BUFFER_FOR_PLAYBACK_MS = 50  // Start playback threshold
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 50

// Other settings
private const val TARGET_BUFFER_BYTES = -1     // No byte-based buffering
private const val UDP_SOCKET_TIMEOUT_MS = 8000 // Socket timeout
```

---

# Network Protocol

## Connection Handshake

1. **Client** → **Server**: `"CONNECT:width:height:framerate"`
2. **Server** → **Client**: `"ACK"` or `"SERVER_READY"`

## Streaming Protocol

- **Control Port**: As advertised by mDNS
- **Stream Port**: Control port + 1
- **Protocol**: UDP
- **Format**: Raw video stream (server-dependent)

## Example Flow

```
1. Client discovers server via mDNS: "MyServer@192.168.1.100:5001"
2. Client sends to 192.168.1.100:5001: "CONNECT:1920:1080:30"
3. Server responds: "SERVER_READY"
4. Client starts UDP streaming on port 5002
5. Server streams video data to client on port 5002
```

---

# Configuration

## Timeout Settings

```kotlin
private const val CONNECTION_TIMEOUT_MS = 10000 // Connection timeout
private const val UDP_SOCKET_TIMEOUT_MS = 8000  // UDP socket timeout
private const val MAX_RETRIES = 3               // Connection retries
```

## Buffer Settings

```kotlin
private const val MIN_BUFFER_MS = 50           // Ultra-low minimum buffer
private const val MAX_BUFFER_MS = 100          // Ultra-low maximum buffer
private const val BUFFER_FOR_PLAYBACK_MS = 50  // Start playback immediately
```

## Customization

To modify latency settings, adjust the constants in `StreamPlayer.kt`:

- **Lower values** = Lower latency, higher chance of stuttering
- **Higher values** = Higher latency, smoother playback

---

# Error Handling

## Connection Errors

- **"Failed to connect to server"**: Server didn't respond to handshake
- **"Connection failed: [exception]"**: Network or parsing error
- **"Max retries exceeded"**: Server unreachable after retries

## Stream Errors

- **"Stream ended"**: Server stopped streaming
- **"Stream error: [error code]"**: ExoPlayer encountered an error

## Recovery

- Connection errors trigger UI reset to disconnected state
- Stream errors allow reconnection attempts
- Network discovery continues running for server updates

---

# Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## Code Style

- Follow Kotlin conventions
- Use meaningful variable names
- Add comments for complex logic
- Handle exceptions appropriately

---

# License

This project is open source. gotta add a license

---

# Server Compatibility

This client is designed to work with servers that:

1. Advertise via mDNS with service type `_screenstream._tcp.`
2. Accept connection messages in format `"CONNECT:width:height:framerate"`
3. Respond with `"ACK"` or `"SERVER_READY"`
4. Stream video data via UDP on port+1

For server implementation examples, see the companion server documentation.
