package com.example.streamclient3

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

/**
 * A utility class to parse H.264 NAL units from UDP packets.
 * Supports handling fragmented NAL units and packet reordering.
 */
class H264PacketParser {
    private val TAG = "H264PacketParser"

    // Constants for H.264 NAL unit types
    private val NAL_HEADER_SIZE = 4 // Size of start code (0x00000001)
    private val NAL_UNIT_TYPE_MASK = 0x1F
    private val NAL_UNIT_TYPE_FU_A = 28 // Fragmentation Unit A

    // NAL unit types we especially care about
    private val NAL_UNIT_TYPE_SPS = 7  // Sequence Parameter Set
    private val NAL_UNIT_TYPE_PPS = 8  // Picture Parameter Set
    private val NAL_UNIT_TYPE_IDR = 5  // IDR frame (keyframe)

    // Buffer to store fragmented NAL units
    private val nalBuffer = ByteArray(1024 * 1024) // 1MB buffer
    private var nalBufferPosition = 0

    // Queue to store complete NAL units ready for decoding
    private val nalQueue = LinkedBlockingQueue<ByteArray>(60) // Maximum 60 NAL units in queue

    // Store SPS and PPS for future reference
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    // Add arrays to store the essential parts of SPS/PPS for comparison
    private var spsEssentialData: ByteArray? = null
    private var ppsEssentialData: ByteArray? = null

    // Track if we've received both SPS and PPS
    private var hasSPS = false
    private var hasPPS = false

    // Track the state of fragmented NAL units
    private var currentFragmentedNalType = -1
    private var isProcessingFragmentedNal = false

    // Added: Track last processed timestamp to prevent rapid duplicate processing
    private var lastSpsProcessedTime = 0L
    private var lastPpsProcessedTime = 0L
    private val DUPLICATE_THRESHOLD_MS = 100 // Minimum time between processing duplicates

    /**
     * Process a UDP packet containing H.264 data
     * @param data The raw UDP packet data
     * @return true if a complete NAL unit was extracted, false otherwise
     */
    fun processPacket(data: ByteArray): Boolean {
        if (data.size < 2) {
            // Packet too small, not a valid H.264 packet
            return false
        }

        // Check if this packet starts with start code
        if (data.size >= 4 &&
            data[0] == 0x00.toByte() &&
            data[1] == 0x00.toByte() &&
            (data[2] == 0x00.toByte() || data[2] == 0x01.toByte()) &&
            data[3] == 0x01.toByte()) {

            // This already has a start code, use it directly
            Log.d(TAG, "Received packet with start code")
            return processPacketWithStartCode(data)
        }

        // Check NAL unit type
        val nalType = data[0].toInt() and NAL_UNIT_TYPE_MASK

        // Validate NAL type (0-31 are valid in H.264)
        if (nalType > 31) {
            Log.e(TAG, "Invalid NAL type received: $nalType - ignoring packet")
            return false
        }

        // Log the NAL type for debugging
        Log.d(TAG, "Received NAL type: $nalType, size: ${data.size}")

        return when {
            nalType == NAL_UNIT_TYPE_FU_A -> {
                // Handle fragmented NAL unit
                processFragmentedNalUnit(data)
            }
            nalType == NAL_UNIT_TYPE_SPS -> {
                // Store SPS for future reference and add to queue
                val spsWithStartCode = addStartCodeToNalUnit(data)

                // Extract essential parts for comparison (skip start code and header)
                val essentialData = extractEssentialData(data)

                // Only update SPS if different from previous one or we don't have one yet
                if (spsEssentialData == null || !essentialData.contentEquals(spsEssentialData!!)) {
                    spsData = spsWithStartCode
                    spsEssentialData = essentialData
                    hasSPS = true
                    nalQueue.offer(spsWithStartCode)
                    lastSpsProcessedTime = System.currentTimeMillis()
                    Log.d(TAG, "Processed SPS NAL unit (new)")
                } else {
                    Log.d(TAG, "Ignored duplicate SPS NAL unit")
                }
                true
            }
            nalType == NAL_UNIT_TYPE_PPS -> {
                // Store PPS for future reference and add to queue
                val ppsWithStartCode = addStartCodeToNalUnit(data)

                // Extract essential parts for comparison (skip start code and header)
                val essentialData = extractEssentialData(data)

                // Only update PPS if different from previous one or we don't have one yet
                if (ppsEssentialData == null || !essentialData.contentEquals(ppsEssentialData!!)) {
                    ppsData = ppsWithStartCode
                    ppsEssentialData = essentialData
                    hasPPS = true
                    nalQueue.offer(ppsWithStartCode)
                    lastPpsProcessedTime = System.currentTimeMillis()
                    Log.d(TAG, "Processed PPS NAL unit (new)")
                } else {
                    Log.d(TAG, "Ignored duplicate PPS NAL unit")
                }
                true
            }
            nalType == NAL_UNIT_TYPE_IDR -> {
                // For IDR frames (keyframes), ensure we've sent SPS and PPS first
                ensureSPSAndPPSAreSent()
                processCompleteNalUnit(data)
            }
            else -> {
                // Handle complete NAL unit
                processCompleteNalUnit(data)
            }
        }
    }

    /**
     * Extract essential data from NAL unit for comparison (skips header)
     */
    private fun extractEssentialData(data: ByteArray): ByteArray {
        // For most NAL units, we can skip the first byte (header)
        // For more complex NAL types like SPS, we might need more sophisticated parsing
        val startOffset = 1 // Skip NAL header byte
        val endOffset = minOf(data.size, 16) // Use first 15 bytes after header for comparison

        val result = ByteArray(endOffset - startOffset)
        System.arraycopy(data, startOffset, result, 0, result.size)
        return result
    }

    /**
     * Process a packet that already has a start code
     */
    private fun processPacketWithStartCode(data: ByteArray): Boolean {
        try {
            // Check NAL type after start code
            if (data.size >= 5) {
                val startCodeSize = if (data[2] == 0x01.toByte()) 3 else 4
                val nalType = data[startCodeSize].toInt() and NAL_UNIT_TYPE_MASK

                // Validate NAL type
                if (nalType > 31) {
                    Log.e(TAG, "Invalid NAL type in packet with start code: $nalType - ignoring")
                    return false
                }

                val currentTime = System.currentTimeMillis()

                if (nalType == NAL_UNIT_TYPE_SPS) {
                    // Extract essential data for proper comparison (skip start code and header)
                    val essentialData = ByteArray(minOf(data.size - startCodeSize - 1, 15))
                    if (data.size > startCodeSize + 1 + essentialData.size) {
                        System.arraycopy(data, startCodeSize + 1, essentialData, 0, essentialData.size)
                    }

                    // Check for duplicates with better logic
                    val isDuplicate = spsEssentialData != null && essentialData.contentEquals(spsEssentialData!!)
                    val isRecentDuplicate = currentTime - lastSpsProcessedTime < DUPLICATE_THRESHOLD_MS

                    if (!isDuplicate || !isRecentDuplicate) {
                        // Store SPS only if it's different or we haven't seen one recently
                        spsData = data.clone()
                        spsEssentialData = essentialData
                        hasSPS = true
                        lastSpsProcessedTime = currentTime
                        Log.d(TAG, "Stored SPS with start code (new)")
                        nalQueue.offer(data.clone())
                    } else {
                        Log.d(TAG, "Ignored duplicate SPS with start code")
                        return true // Still counts as processing something
                    }
                } else if (nalType == NAL_UNIT_TYPE_PPS) {
                    // Extract essential data for proper comparison (skip start code and header)
                    val essentialData = ByteArray(minOf(data.size - startCodeSize - 1, 15))
                    if (data.size > startCodeSize + 1 + essentialData.size) {
                        System.arraycopy(data, startCodeSize + 1, essentialData, 0, essentialData.size)
                    }

                    // Check for duplicates with better logic
                    val isDuplicate = ppsEssentialData != null && essentialData.contentEquals(ppsEssentialData!!)
                    val isRecentDuplicate = currentTime - lastPpsProcessedTime < DUPLICATE_THRESHOLD_MS

                    if (!isDuplicate || !isRecentDuplicate) {
                        // Store PPS only if it's different or we haven't seen one recently
                        ppsData = data.clone()
                        ppsEssentialData = essentialData
                        hasPPS = true
                        lastPpsProcessedTime = currentTime
                        Log.d(TAG, "Stored PPS with start code (new)")
                        nalQueue.offer(data.clone())
                    } else {
                        Log.d(TAG, "Ignored duplicate PPS with start code")
                        return true // Still counts as processing something
                    }
                } else if (nalType == NAL_UNIT_TYPE_IDR) {
                    // For IDR frames, ensure we've sent SPS and PPS first
                    ensureSPSAndPPSAreSent()

                    // Add the IDR frame to the queue
                    nalQueue.offer(data.clone())
                    return true
                } else {
                    // For other NAL types, just add to queue without duplicate checking
                    nalQueue.offer(data.clone())
                    return true
                }

                return true
            } else {
                Log.w(TAG, "Packet with start code too small: ${data.size} bytes")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet with start code: ${e.message}")
            return false
        }
    }

    /**
     * Ensure SPS and PPS NALs are sent before IDR frames
     */
    private fun ensureSPSAndPPSAreSent() {
        // If we have SPS and PPS stored, add them to the queue before an IDR frame
        if (hasSPS && hasPPS) {
            spsData?.let {
                nalQueue.offer(it.clone())
                Log.d(TAG, "Re-sending SPS before IDR")
            }

            ppsData?.let {
                nalQueue.offer(it.clone())
                Log.d(TAG, "Re-sending PPS before IDR")
            }
        } else {
            Log.w(TAG, "Received IDR frame but missing SPS/PPS - decoding may fail")
        }
    }

    /**
     * Add start code to a NAL unit
     */
    private fun addStartCodeToNalUnit(data: ByteArray): ByteArray {
        val nalUnitWithStartCode = ByteArray(data.size + NAL_HEADER_SIZE)

        // Add start code
        nalUnitWithStartCode[0] = 0x00
        nalUnitWithStartCode[1] = 0x00
        nalUnitWithStartCode[2] = 0x00
        nalUnitWithStartCode[3] = 0x01

        // Add NAL unit data
        System.arraycopy(data, 0, nalUnitWithStartCode, NAL_HEADER_SIZE, data.size)

        return nalUnitWithStartCode
    }
    /**
     * Add this method to your H264PacketParser class for enhanced debugging
     */
    fun logNalUnitInfo(nalUnit: ByteArray) {
        if (nalUnit.size < 5) {
            Log.d(TAG, "NAL unit too small: ${nalUnit.size} bytes")
            return
        }

        // Check for start code
        val hasStartCode = (nalUnit[0] == 0x00.toByte() &&
                nalUnit[1] == 0x00.toByte() &&
                ((nalUnit[2] == 0x00.toByte() && nalUnit[3] == 0x01.toByte()) ||
                        nalUnit[2] == 0x01.toByte()))

        val startCodeOffset = if (hasStartCode) {
            if (nalUnit[2] == 0x01.toByte()) 3 else 4
        } else {
            0
        }

        if (startCodeOffset >= nalUnit.size) {
            Log.d(TAG, "Invalid NAL unit: no data after start code")
            return
        }

        // Get NAL type
        val nalHeader = nalUnit[startCodeOffset].toInt()
        val nalType = nalHeader and NAL_UNIT_TYPE_MASK

        // Get some additional info
        val nalRefIdc = (nalHeader and 0x60) shr 5

        // NAL unit type description
        val typeDesc = when(nalType) {
            0 -> "Unspecified"
            1 -> "Coded slice (non-IDR)"
            2 -> "Coded slice data partition A"
            3 -> "Coded slice data partition B"
            4 -> "Coded slice data partition C"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            9 -> "Access Unit Delimiter"
            10 -> "End of Sequence"
            11 -> "End of Stream"
            12 -> "Filler Data"
            13 -> "SPS Extension"
            14 -> "Prefix NAL Unit"
            15 -> "Subset SPS"
            16 -> "Reserved"
            17 -> "Reserved"
            18 -> "Reserved"
            19 -> "Coded slice (auxiliary)"
            20 -> "Extension"
            21 -> "Depth/3D extension"
            22, 23 -> "Reserved"

            else -> "Unknown"
        }

        // The actual NAL data size (without start code)
        val dataSize = nalUnit.size - startCodeOffset - 1

        // Print detailed info
        Log.d(TAG, "NAL: type=$nalType ($typeDesc), ref=$nalRefIdc, size=${nalUnit.size} bytes, " +
                "data size=$dataSize bytes, has start code=$hasStartCode")

        // For SPS and PPS, print the first few bytes for debugging
        if (nalType == 7 || nalType == 8) {
            val headerBytes = StringBuilder()
            val maxBytes = minOf(16, nalUnit.size - startCodeOffset)

            for (i in startCodeOffset until startCodeOffset + maxBytes) {
                headerBytes.append(String.format("%02X ", nalUnit[i].toInt() and 0xFF))
            }

            Log.d(TAG, "NAL header bytes: $headerBytes")
        }
    }
    /**
     * Handle a fragmented NAL unit (FU-A)
     * @param data The raw UDP packet data
     * @return true if this was the last fragment of a NAL unit, false otherwise
     */
    private fun processFragmentedNalUnit(data: ByteArray): Boolean {
        if (data.size < 3) {
            // Not enough data for a valid FU-A
            Log.e(TAG, "FU-A packet too small: ${data.size} bytes")
            return false
        }

        val fuHeader = data[1].toInt()
        val isStart = (fuHeader and 0x80) != 0
        val isEnd = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x1F

        // Validate NAL type
        if (nalType > 31) {
            Log.e(TAG, "Invalid NAL type in FU-A: $nalType - ignoring")
            isProcessingFragmentedNal = false
            return false
        }

        Log.d(TAG, "FU-A: start=$isStart, end=$isEnd, type=$nalType")

        if (isStart) {
            // Start of a new fragmented NAL unit
            nalBufferPosition = 0
            isProcessingFragmentedNal = true
            currentFragmentedNalType = nalType

            // Reconstruct the original NAL header
            val originalNalHeader = (data[0].toInt() and 0xE0) or nalType

            // Add start code to the buffer
            nalBuffer[nalBufferPosition++] = 0x00
            nalBuffer[nalBufferPosition++] = 0x00
            nalBuffer[nalBufferPosition++] = 0x00
            nalBuffer[nalBufferPosition++] = 0x01

            // Add reconstructed NAL header
            nalBuffer[nalBufferPosition++] = originalNalHeader.toByte()

            // Add payload (skip the first 2 bytes which are the NAL header and FU header)
            if (data.size > 2) {  // Ensure there's payload data
                System.arraycopy(data, 2, nalBuffer, nalBufferPosition, data.size - 2)
                nalBufferPosition += data.size - 2
            }

            return false // Not complete yet
        } else if (isProcessingFragmentedNal && nalType == currentFragmentedNalType) {
            // Only accept fragments if we're already processing a fragmented NAL
            // and the type matches the current fragment we're processing

            // Middle or end fragment
            // Check for buffer overflow
            if (nalBufferPosition + data.size - 2 > nalBuffer.size) {
                Log.e(TAG, "NAL buffer overflow, resetting fragmented NAL processing")
                isProcessingFragmentedNal = false
                return false
            }

            // Add payload (skip the first 2 bytes)
            if (data.size > 2) {  // Ensure there's payload data
                System.arraycopy(data, 2, nalBuffer, nalBufferPosition, data.size - 2)
                nalBufferPosition += data.size - 2
            }

            if (isEnd) {
                // This is the last fragment, add the complete NAL unit to the queue
                isProcessingFragmentedNal = false

                // Validate we have a reasonable amount of data
                if (nalBufferPosition < 6) {  // At least start code + header + some data
                    Log.e(TAG, "Completed fragmented NAL too small: $nalBufferPosition bytes")
                    return false
                }

                val completeNalUnit = ByteArray(nalBufferPosition)
                System.arraycopy(nalBuffer, 0, completeNalUnit, 0, nalBufferPosition)

                try {
                    // Check if this completed NAL is an IDR frame and if so, ensure SPS/PPS are sent first
                    if (nalBufferPosition > 5) {
                        val completedNalType = completeNalUnit[4].toInt() and NAL_UNIT_TYPE_MASK
                        if (completedNalType == NAL_UNIT_TYPE_IDR) {
                            ensureSPSAndPPSAreSent()
                        }
                    }

                    // Add to queue, with non-blocking behavior (drop if queue is full)
                    val success = nalQueue.offer(completeNalUnit)
                    if (!success) {
                        Log.w(TAG, "Queue full, dropping completed fragmented NAL")
                    }
                    return success
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding NAL to queue: ${e.message}")
                    return false
                }
            }
        } else {
            // Received fragment without start or not matching current fragment
            if (!isProcessingFragmentedNal) {
                Log.w(TAG, "Received fragment but not processing any fragmented NAL - ignoring")
            } else if (nalType != currentFragmentedNalType) {
                Log.w(TAG, "Received fragment with mismatched type (got $nalType, expected $currentFragmentedNalType) - ignoring")
            }
        }

        return false
    }

    /**
     * Handle a complete NAL unit
     * @param data The raw UDP packet data
     * @return true if the NAL unit was successfully queued, false otherwise
     */
    private fun processCompleteNalUnit(data: ByteArray): Boolean {
        // Create a new array with start code and NAL unit data
        val nalUnitWithStartCode = ByteArray(data.size + NAL_HEADER_SIZE)

        // Add start code
        nalUnitWithStartCode[0] = 0x00
        nalUnitWithStartCode[1] = 0x00
        nalUnitWithStartCode[2] = 0x00
        nalUnitWithStartCode[3] = 0x01

        // Add NAL unit data
        System.arraycopy(data, 0, nalUnitWithStartCode, NAL_HEADER_SIZE, data.size)

        try {
            // Add to queue, with non-blocking behavior
            val success = nalQueue.offer(nalUnitWithStartCode)
            if (!success) {
                Log.w(TAG, "Queue full, dropping NAL unit")
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error adding NAL to queue: ${e.message}")
            return false
        }
    }

    /**
     * Get the next complete NAL unit from the queue
     * @return ByteArray containing a complete NAL unit, or null if none available
     */
    fun getNextNalUnit(): ByteArray? {
        return nalQueue.poll()
    }

    /**
     * Check if there are NAL units available for processing
     * @return true if NAL units are available, false otherwise
     */
    fun hasNalUnits(): Boolean {
        return !nalQueue.isEmpty()
    }

    /**
     * Check if we have received both SPS and PPS data
     * @return true if both SPS and PPS are available, false otherwise
     */
    fun hasCodecConfigData(): Boolean {
        return hasSPS && hasPPS
    }

    /**
     * Clear all buffered NAL units
     */
    fun clear() {
        nalQueue.clear()
        nalBufferPosition = 0
        spsData = null
        ppsData = null
        spsEssentialData = null
        ppsEssentialData = null
        hasSPS = false
        hasPPS = false
        isProcessingFragmentedNal = false
        currentFragmentedNalType = -1
        lastSpsProcessedTime = 0L
        lastPpsProcessedTime = 0L
    }

    /**
     * Get codec specific data (SPS and PPS) if available
     * @return Pair of ByteArray for SPS and PPS, or null if not available
     */
    fun getCodecSpecificData(): Pair<ByteArray?, ByteArray?>? {
        if (spsData != null && ppsData != null) {
            return Pair(spsData, ppsData)
        }
        return null
    }
}