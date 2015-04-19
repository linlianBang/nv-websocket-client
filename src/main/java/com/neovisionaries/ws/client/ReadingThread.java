/*
 * Copyright (C) 2015 Neo Visionaries Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neovisionaries.ws.client;


import static com.neovisionaries.ws.client.WebSocketOpcode.BINARY;
import static com.neovisionaries.ws.client.WebSocketOpcode.CLOSE;
import static com.neovisionaries.ws.client.WebSocketOpcode.CONTINUATION;
import static com.neovisionaries.ws.client.WebSocketOpcode.PING;
import static com.neovisionaries.ws.client.WebSocketOpcode.PONG;
import static com.neovisionaries.ws.client.WebSocketOpcode.TEXT;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import com.neovisionaries.ws.client.StateManager.CloseInitiator;


class ReadingThread extends Thread
{
    private static final long INTERRUPTION_TIMER_DELAY = 60 * 1000;
    private final WebSocket mWebSocket;
    private final Map<String, List<String>> mHeaders;
    private boolean mStopRequested;
    private WebSocketFrame mCloseFrame;
    private List<WebSocketFrame> mContinuation = new ArrayList<WebSocketFrame>();


    public ReadingThread(WebSocket websocket, Map<String, List<String>> headers)
    {
        super("ReadingThread");

        mWebSocket = websocket;
        mHeaders   = headers;
    }


    @Override
    public void run()
    {
        // Notify listeners that the handshake succeeded.
        callOnConnected();

        while (true)
        {
            synchronized (this)
            {
                if (mStopRequested)
                {
                    break;
                }
            }

            // Receive a frame from the server.
            WebSocketFrame frame = readFrame();

            if (frame == null)
            {
                // Something unexpected happened.
                break;
            }

            // Handle the frame.
            boolean keepReading = handleFrame(frame);

            if (keepReading == false)
            {
                break;
            }
        }

        // Wait for a close frame if one has not been received yet.
        waitForCloseFrame();

        // Notify this reading thread finished.
        notifyFinished();
    }


    void requestStop()
    {
        synchronized (this)
        {
            mStopRequested = true;
            interrupt();
        }
    }


    /**
     * Call {@link WebSocketListener#onConnected(WebSocket, Map) onConnected} method
     * of the listeners.
     */
    private void callOnConnected()
    {
        mWebSocket.getListenerManager().callOnConnected(mHeaders);
    }


    /**
     * Call {@link WebSocketListener#onFrame(WebSocket, WebSocketFrame) onFrame}
     * method of the listeners.
     */
    private void callOnFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onContinuationFrame(WebSocket, WebSocketFrame)
     * onContinuationFrame} method of the listeners.
     */
    private void callOnContinuationFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnContinuationFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onTextFrame(WebSocket, WebSocketFrame)
     * onTextFrame} method of the listeners.
     */
    private void callOnTextFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnTextFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onBinaryFrame(WebSocket, WebSocketFrame)
     * onBinaryFrame} method of the listeners.
     */
    private void callOnBinaryFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnBinaryFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onCloseFrame(WebSocket, WebSocketFrame)
     * onCloseFrame} method of the listeners.
     */
    private void callOnCloseFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnCloseFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onPingFrame(WebSocket, WebSocketFrame)
     * onPingFrame} method of the listeners.
     */
    private void callOnPingFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnPingFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onPongFrame(WebSocket, WebSocketFrame)
     * onPongFrame} method of the listeners.
     */
    private void callOnPongFrame(WebSocketFrame frame)
    {
        mWebSocket.getListenerManager().callOnPongFrame(frame);
    }


    /**
     * Call {@link WebSocketListener#onTextMessage(WebSocket, String)
     * onTextMessage} method of the listeners.
     */
    private void callOnTextMessage(byte[] data)
    {
        try
        {
            // Interpret the byte array as a string.
            // OutOfMemoryError may happen when the size of data is too big.
            String message = Misc.toStringUTF8(data);

            // Call onTextMessage() method of the listeners.
            callOnTextMessage(message);
        }
        catch (Throwable e)
        {
            // Failed to convert payload data into a string.
            WebSocketException wse = new WebSocketException(
                WebSocketError.TEXT_MESSAGE_CONSTRUCTION_ERROR,
                "Failed to convert payload data into a string.", e);

            // Notify the listeners that text message construction failed.
            callOnTextMessageError(data, wse);
        }
    }


    /**
     * Call {@link WebSocketListener#onTextMessage(WebSocket, String)
     * onTextMessage} method of the listeners.
     */
    private void callOnTextMessage(String message)
    {
        mWebSocket.getListenerManager().callOnTextMessage(message);
    }


    /**
     * Call {@link WebSocketListener#onBinaryMessage(WebSocket, String)
     * onBinaryMessage} method of the listeners.
     */
    private void callOnBinaryMessage(byte[] message)
    {
        mWebSocket.getListenerManager().callOnBinaryMessage(message);
    }


    /**
     * Call {@link WebSocketListener#onFrameError(WebSocket, WebSocketFrame,
     * WebSocketException) onFrameError} method of the listeners.
     */
    private void callOnFrameError(WebSocketFrame frame, WebSocketException cause)
    {
        mWebSocket.getListenerManager().callOnFrameError(frame, cause);
    }


    /**
     * Call {@link WebSocketListener#onMessageError(WebSocket, List, WebSocketException)
     * onMessageError} method of the listeners.
     */
    private void callOnMessageError(List<WebSocketFrame> frames, WebSocketException cause)
    {
        mWebSocket.getListenerManager().callOnMessageError(frames, cause);
    }


    /**
     * Call {@link WebSocketListener#onTextMessageError(WebSocket, byte[], WebSocketException)
     * onTextMessageError} method of the listeners.
     */
    private void callOnTextMessageError(byte[] data, WebSocketException cause)
    {
        mWebSocket.getListenerManager().callOnTextMessageError(data, cause);
    }


    private WebSocketFrame readFrame()
    {
        WebSocketFrame frame = null;
        WebSocketException wse = null;
        boolean intentionallyInterrupted = false;

        try
        {
            // Receive a frame from the server.
            frame = mWebSocket.getInput().readFrame();

            // Verify the frame. If invalid, WebSocketException is thrown.
            verifyFrame(frame);

            // Return the verified frame.
            return frame;
        }
        catch (InterruptedIOException e)
        {
            if (mStopRequested)
            {
                // Intentionally interrupted.
                intentionallyInterrupted = true;
            }
            else
            {
                // Interruption occurred while a frame was being read from the web socket.
                wse = new WebSocketException(
                    WebSocketError.INTERRUPTED_IN_READING,
                    "Interruption occurred while a frame was being read from the web socket.", e);
            }
        }
        catch (IOException e)
        {
            // An I/O error occurred while a frame was being read from the web socket.
            wse = new WebSocketException(
                WebSocketError.IO_ERROR_IN_READING,
                "An I/O error occurred while a frame was being read from the web socket.", e);
        }
        catch (WebSocketException e)
        {
            // A protocol error.
            wse = e;
        }

        if (intentionallyInterrupted == false)
        {
            // Notify the listeners that an error occurred while a frame was being read.
            callOnFrameError(frame, wse);

            // Create a close frame.
            WebSocketFrame closeFrame = createCloseFrame(wse);

            // Send the close frame.
            mWebSocket.sendFrame(closeFrame);
        }

        // A frame is not available.
        return null;
    }


    private void verifyFrame(WebSocketFrame frame) throws WebSocketException
    {
        // Verify RSV1, RSV2 and RSV3.
        verifyReservedBits(frame);

        // The opcode of the frame must be known.
        verifyFrameOpcode(frame);

        // Frames from the server must not be masked.
        verifyFrameMask(frame);

        // Verify fragmentation conditions.
        verifyFrameFragmentation(frame);

        // Verify the size of the payload.
        verifyFrameSize(frame);
    }


    private void verifyReservedBits(WebSocketFrame frame) throws WebSocketException
    {
        // If extended use of web socket frames is allowed.
        if (mWebSocket.isExtended())
        {
            // Do not check RSV1/RSV2/RSV3 bits.
            return;
        }

        // RSV1, RSV2, RSV3
        //
        // The specification requires that these bits "be 0 unless an extension
        // is negotiated that defines meanings for non-zero values".

        if (frame.getRsv1() || frame.getRsv2() || frame.getRsv3())
        {
            String message = String.format(
                "At least one of the reserved bits of a frame is set: RSV1=%s,RSV2=%s,RSV3=%s",
                frame.getRsv1(), frame.getRsv2(), frame.getRsv3());

            // At least one of the reserved bits of a frame is set.
            throw new WebSocketException(
                WebSocketError.NON_ZERO_RESERVED_BITS, message);
        }
    }


    /**
     * Ensure that the opcode of the give frame is a known one.
     *
     * <blockquote>
     * <p>From RFC 6455, 5.2. Base Framing Protocol</p>
     * <p><i>
     * If an unknown opcode is received, the receiving endpoint MUST
     * Fail the WebSocket Connection.
     * </i></p>
     * </blockquote>
     */
    private void verifyFrameOpcode(WebSocketFrame frame) throws WebSocketException
    {
        switch (frame.getOpcode())
        {
            case CONTINUATION:
            case TEXT:
            case BINARY:
            case CLOSE:
            case PING:
            case PONG:
                // Known opcode
                return;

            default:
                break;
        }

        // If extended use of web socket frames is allowed.
        if (mWebSocket.isExtended())
        {
            // Allow the unknown opcode.
            return;
        }

        // A frame has an unknown opcode.
        throw new WebSocketException(
            WebSocketError.UNKNOWN_OPCODE,
            "A frame has an unknown opcode: 0x" + Integer.toHexString(frame.getOpcode()));
    }


    /**
     * Ensure that the given frame is not masked.
     *
     * <blockquote>
     * <p>From RFC 6455, 5.1. Overview:</p>
     * <p><i>
     * A server MUST NOT mask any frames that it sends to the client.
     * A client MUST close a connection if it detects a masked frame.
     * </i></p>
     * </blockquote>
     */
    private void verifyFrameMask(WebSocketFrame frame) throws WebSocketException
    {
        // If the frame is masked.
        if (frame.getMask())
        {
            // A frame from the server is masked.
            throw new WebSocketException(
                WebSocketError.FRAME_MASKED,
                "A frame from the server is masked.");
        }
    }


    private void verifyFrameFragmentation(WebSocketFrame frame) throws WebSocketException
    {
        // Control frames (see Section 5.5) MAY be injected in the
        // middle of a fragmented message. Control frames themselves
        // MUST NOT be fragmented.
        if (frame.isControlFrame())
        {
            // If fragmented.
            if (frame.getFin() == false)
            {
                // A control frame is fragmented.
                throw new WebSocketException(
                    WebSocketError.FRAGMENTED_CONTROL_FRAME,
                    "A control frame is fragmented.");
            }

            // No more requirements on a control frame.
            return;
        }

        // True if a continuation has already started.
        boolean continuationExists = (mContinuation.size() == 0);

        // If the frame is a continuation frame.
        if (frame.isContinuationFrame())
        {
            // There must already exist a continuation sequence.
            if (continuationExists == false)
            {
                // A continuation frame was detected although a continuation had not started.
                throw new WebSocketException(
                    WebSocketError.UNEXPECTED_CONTINUATION_FRAME,
                    "A continuation frame was detected although a continuation had not started.");
            }

            // No more requirements on a continuation frame.
            return;
        }

        // A data frame.

        if (continuationExists)
        {
            // A non-control frame was detected although the existing continuation had not been closed.
            throw new WebSocketException(
                WebSocketError.CONTINUATION_NOT_CLOSED,
                "A non-control frame was detected although the existing continuation had not been closed.");
        }
    }


    private void verifyFrameSize(WebSocketFrame frame) throws WebSocketException
    {
        // If the frame is not a control frame.
        if (frame.isControlFrame() == false)
        {
            // Nothing to check.
            return;
        }

        // RFC 6455, 5.5. Control Frames.
        //
        //   All control frames MUST have a payload length of 125 bytes or less
        //   and MUST NOT be fragmented.
        //

        byte[] payload = frame.getPayload();

        if (payload == null)
        {
            // The frame does not have payload.
            return;
        }

        if (125 < payload.length)
        {
            // The payload size of a control frame exceeds the maximum size (125 bytes).
            throw new WebSocketException(
                WebSocketError.TOO_LONG_CONTROL_FRAME_PAYLOAD,
                "The payload size of a control frame exceeds the maximum size (125 bytes): " + payload.length);
        }
    }


    private WebSocketFrame createCloseFrame(WebSocketException wse)
    {
        int closeCode;

        switch (wse.getError())
        {
            // In WebSocketInputStream.readFrame()

            case INSUFFICENT_DATA:
            case INVALID_PAYLOAD_LENGTH:
                closeCode = WebSocketCloseCode.UNCONFORMED;
                break;

            case TOO_LONG_PAYLOAD:
            case INSUFFICIENT_MEMORY_FOR_PAYLOAD:
                closeCode = WebSocketCloseCode.OVERSIZE;
                break;

            // In this.verifyFrame(WebSocketFrame)

            case NON_ZERO_RESERVED_BITS:
            case UNKNOWN_OPCODE:
            case FRAME_MASKED:
            case FRAGMENTED_CONTROL_FRAME:
            case UNEXPECTED_CONTINUATION_FRAME:
            case CONTINUATION_NOT_CLOSED:
            case TOO_LONG_CONTROL_FRAME_PAYLOAD:
                closeCode = WebSocketCloseCode.UNCONFORMED;
                break;

            // In this.readFrame()

            case INTERRUPTED_IN_READING:
            case IO_ERROR_IN_READING:
                closeCode = WebSocketCloseCode.VIOLATED;
                break;

            // Others (unexpected)

            default:
                closeCode = WebSocketCloseCode.VIOLATED;
                break;
        }

        return WebSocketFrame.createCloseFrame(closeCode, wse.getMessage());
    }


    private boolean handleFrame(WebSocketFrame frame)
    {
        // Notify the listeners that a frame was received.
        callOnFrame(frame);

        // Dispatch based on the opcode.
        switch (frame.getOpcode())
        {
            case CONTINUATION:
                return handleContinuationFrame(frame);

            case TEXT:
                return handleTextFrame(frame);

            case BINARY:
                return handleBinaryFrame(frame);

            case CLOSE:
                return handleCloseFrame(frame);

            case PING:
                return handlePingFrame(frame);

            case PONG:
                return handlePongFrame(frame);

            default:
                // Ignore the frame whose opcode is unknown. Keep reading.
                return true;
        }
    }


    private boolean handleContinuationFrame(WebSocketFrame frame)
    {
        // Notify the listeners that a continuation frame was received.
        callOnContinuationFrame(frame);

        // Append the continuation frame to the existing continuation sequence.
        mContinuation.add(frame);

        // If the frame is not the last one for the continuation.
        if (frame.getFin() == false)
        {
            // Keep reading.
            return true;
        }

        // Concatenate payloads of the frames.
        byte[] data = concatenatePayloads(mContinuation);

        // If the concatenation failed.
        if (data == null)
        {
            // Stop reading.
            return false;
        }

        // If the continuation forms a text message.
        if (mContinuation.get(0).isTextFrame())
        {
            // Notify the listeners that a text message was received.
            callOnTextMessage(data);
        }
        else
        {
            // Notify the listeners that a binary message was received.
            callOnBinaryMessage(data);
        }

        // Clear the continuation.
        mContinuation.clear();

        // Keep reading.
        return true;
    }


    private byte[] concatenatePayloads(List<WebSocketFrame> frames)
    {
        Throwable cause;

        try
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // For each web socket frame.
            for (WebSocketFrame frame : frames)
            {
                // Get the payload of the frame.
                byte[] payload = frame.getPayload();

                // If the payload is null or empty.
                if (payload == null || payload.length == 0)
                {
                    continue;
                }

                // Append the payload.
                baos.write(payload);
            }

            // Return the concatenated byte array.
            return baos.toByteArray();
        }
        catch (IOException e)
        {
            cause = e;
        }
        catch (OutOfMemoryError e)
        {
            cause = e;
        }

        // Create a WebSocketException which has a cause.
        WebSocketException wse = new WebSocketException(
            WebSocketError.MESSAGE_CONSTRUCTION_ERROR,
            "Failed to concatenate payloads of multiple frames to construct a message.", cause);

        // Notify the listeners that message construction failed.
        callOnMessageError(frames, wse);

        // Create a close frame with a close code of 1009 which
        // indicates that the message is too big to process.
        WebSocketFrame frame = WebSocketFrame
            .createCloseFrame(WebSocketCloseCode.OVERSIZE, wse.getMessage());

        // Send the close frame.
        mWebSocket.sendFrame(frame);

        // Failed to construct a message.
        return null;
    }


    private boolean handleTextFrame(WebSocketFrame frame)
    {
        // Notify the listeners that a text frame was received.
        callOnTextFrame(frame);

        // If the frame indicates the start of fragmentation.
        if (frame.getFin() == false)
        {
            // Start a continuation sequence.
            mContinuation.add(frame);

            // Keep reading.
            return true;
        }

        // Notify the listeners that a text message was received.
        callOnTextMessage(frame.getPayload());

        // Keep reading.
        return true;
    }


    private boolean handleBinaryFrame(WebSocketFrame frame)
    {
        // Notify the listeners that a binary frame was received.
        callOnBinaryFrame(frame);

        // If the frame indicates the start of fragmentation.
        if (frame.getFin() == false)
        {
            // Start a continuation sequence.
            mContinuation.add(frame);

            // Keep reading.
            return true;
        }

        // Notify the listeners that a binary message was received.
        callOnBinaryMessage(frame.getPayload());

        // Keep reading.
        return true;
    }


    private boolean handleCloseFrame(WebSocketFrame frame)
    {
        // Get the manager which manages the state of the web socket.
        StateManager manager = mWebSocket.getStateManager();

        // The close frame sent from the server.
        mCloseFrame = frame;

        synchronized (manager)
        {
            // If the current state is neither CLOSING nor CLOSED.
            if (manager.isClosing() == false && manager.isClosed() == false)
            {
                // Change the state to CLOSING.
                manager.changeToClosing(CloseInitiator.SERVER);

                // This web socket has not sent a close frame yet,
                // so schedule sending a close frame.

                // RFC 6455, 5.5.1. Close
                //
                //   When sending a Close frame in response, the endpoint
                //   typically echos the status code it received.
                //

                // Simply reuse the frame.
                mWebSocket.sendFrame(frame);
            }
        }

        // Notify the listeners that a close frame was received.
        callOnCloseFrame(frame);

        // Stop reading.
        return false;
    }


    private boolean handlePingFrame(WebSocketFrame frame)
    {
        // Notify the listeners that a ping frame was received.
        callOnPingFrame(frame);

        // RFC 6455, 5.5.3. Pong
        //
        //   A Pong frame sent in response to a Ping frame must
        //   have identical "Application data" as found in the
        //   message body of the Ping frame being replied to.

        // Create a pong frame which has the same payload as
        // the ping frame.
        WebSocketFrame pong = WebSocketFrame
            .createPongFrame(frame.getPayload());

        // Send the pong frame to the server.
        mWebSocket.sendFrame(pong);

        // Keep reading.
        return true;
    }


    private boolean handlePongFrame(WebSocketFrame frame)
    {
        // Notify the listeners that a pong frame was received.
        callOnPongFrame(frame);

        // Keep reading.
        return true;
    }


    private void waitForCloseFrame()
    {
        // If a close frame has already been received.
        if (mCloseFrame != null)
        {
            return;
        }

        WebSocketFrame frame = null;

        // Schedule a timer to prevent from waiting forever.
        Timer timer = scheduleInterruptionTimer();

        while (true)
        {
            try
            {
                // Read a frame from the server.
                frame = mWebSocket.getInput().readFrame();
            }
            catch (Exception e)
            {
                // Give up receiving a close frame.
                break;
            }

            // If it is a close frame.
            if (frame.isCloseFrame())
            {
                // Received a close frame. Finished.
                mCloseFrame = frame;
                break;
            }
        }

        // Cancel the timer for the case where a close frame was received.
        timer.cancel();
    }


    private Timer scheduleInterruptionTimer()
    {
        Timer timer = new Timer("ReadingThreadInterruptionTimer");

        timer.schedule(new TimerTask() {
            @Override
            public void run()
            {
                if (ReadingThread.this.isAlive())
                {
                    // Interrupt WebSocketInputStream.readFrame().
                    ReadingThread.this.interrupt();
                }
            }
        }, INTERRUPTION_TIMER_DELAY);

        return timer;
    }


    private void notifyFinished()
    {
        mWebSocket.onReadingThreadFinished(mCloseFrame);
    }
}
