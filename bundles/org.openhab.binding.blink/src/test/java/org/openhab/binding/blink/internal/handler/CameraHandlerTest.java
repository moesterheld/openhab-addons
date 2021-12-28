/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.blink.internal.handler;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.any;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.blink.internal.BlinkTestUtil;
import org.openhab.binding.blink.internal.config.CameraConfiguration;
import org.openhab.binding.blink.internal.dto.BlinkAccount;
import org.openhab.binding.blink.internal.dto.BlinkCamera;
import org.openhab.binding.blink.internal.service.CameraService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.osgi.service.http.HttpService;

import com.google.gson.Gson;

/**
 * Test class.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
@SuppressWarnings("ConstantConditions")
@ExtendWith(MockitoExtension.class)
@NonNullByDefault
public class CameraHandlerTest {

    private static final String CAMERA_ID = "123";
    private static final String NETWORK_ID = "567";
    static final ThingTypeUID THING_TYPE_UID = new ThingTypeUID("blink", "camera");
    private static final ChannelUID CHANNEL_CAMERA_TEMPERATURE = new ChannelUID(new ThingUID(THING_TYPE_UID, CAMERA_ID),
            "temperature");
    private static final ChannelUID CHANNEL_CAMERA_BATTERY = new ChannelUID(new ThingUID(THING_TYPE_UID, CAMERA_ID),
            "battery");
    private static final ChannelUID CHANNEL_CAMERA_MOTIONDETECTION = new ChannelUID(
            new ThingUID(THING_TYPE_UID, CAMERA_ID), "motiondetection");
    private static final ChannelUID CHANNEL_CAMERA_SETTHUMBNAIL = new ChannelUID(
            new ThingUID(THING_TYPE_UID, CAMERA_ID), "setThumbnail");
    private static final ChannelUID CHANNEL_CAMERA_GETTHUMBNAIL = new ChannelUID(
            new ThingUID(THING_TYPE_UID, CAMERA_ID), "getThumbnail");
    @NonNullByDefault({})
    CameraHandler cameraHandler;
    @Mock
    @NonNullByDefault({})
    ThingHandlerCallback callback;

    @Spy
    Thing thing = new ThingImpl(THING_TYPE_UID, CAMERA_ID);
    @Mock
    @NonNullByDefault({})
    HttpClientFactory httpClientFactory;
    @Mock
    @NonNullByDefault({})
    HttpService httpService;
    @Mock
    @NonNullByDefault({})
    NetworkAddressService networkAddressService;
    @Mock
    @NonNullByDefault({})
    Bridge account;
    @Mock
    @NonNullByDefault({})
    AccountHandler accountHandler;
    @Mock
    @NonNullByDefault({})
    CameraService cameraService;

    @BeforeEach
    void setup() {
        when(httpClientFactory.getCommonHttpClient()).thenReturn(new HttpClient());
        Configuration config = new Configuration();
        config.put("cameraId", CAMERA_ID);
        config.put("networkId", NETWORK_ID);
        when(thing.getConfiguration()).thenReturn(config);
        doReturn(accountHandler).when(account).getHandler();
        cameraHandler = spy(
                new CameraHandler(thing, httpService, networkAddressService, httpClientFactory, new Gson()) {
                    @SuppressWarnings("ConstantConditions")
                    @Override
                    protected @Nullable Bridge getBridge() {
                        return account;
                    }
                });
        cameraHandler.setCallback(callback);
        cameraHandler.initialize();
    }

    @Test
    void testInitialize() {
        assertThat(cameraHandler.config, is(notNullValue()));
        assertThat(cameraHandler.config.cameraId, is(Long.parseLong(CAMERA_ID)));
        assertThat(cameraHandler.config.networkId, is(Long.parseLong(NETWORK_ID)));
        ArgumentCaptor<ThingStatusInfo> statusCaptor = ArgumentCaptor.forClass(ThingStatusInfo.class);
        verify(callback).statusUpdated(eq(thing), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus(), is(ThingStatus.ONLINE));
    }

    @Test
    void testSetOfflineOnHandleCommandException() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        doThrow(IOException.class).when(accountHandler).getTemperature(any());
        cameraHandler.handleCommand(CHANNEL_CAMERA_TEMPERATURE, RefreshType.REFRESH);
        verify(accountHandler).setOffline(any(IOException.class));
        assertThat(cameraHandler.lastThumbnailPath, is(emptyString()));
    }

    @Test
    void testRefreshTemperatureChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        double toBeReturned = 25.0;
        doReturn(toBeReturned).when(accountHandler).getTemperature(any());
        cameraHandler.handleCommand(CHANNEL_CAMERA_TEMPERATURE, RefreshType.REFRESH);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(accountHandler).getTemperature(config);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_TEMPERATURE), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(new QuantityType<>(toBeReturned, ImperialUnits.FAHRENHEIT)));
    }

    @Test
    void testRefreshBatteryChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        doReturn(OnOffType.ON).when(accountHandler).getBattery(any());
        cameraHandler.handleCommand(CHANNEL_CAMERA_BATTERY, RefreshType.REFRESH);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(accountHandler).getBattery(config);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_BATTERY), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(OnOffType.ON));
    }

    @Test
    void testRefreshMotionDetectionChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        doReturn(OnOffType.ON).when(accountHandler).getMotionDetection(any(), eq(false));
        cameraHandler.handleCommand(CHANNEL_CAMERA_MOTIONDETECTION, RefreshType.REFRESH);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(accountHandler).getMotionDetection(config, false);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_MOTIONDETECTION), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(OnOffType.ON));
    }

    @Test
    void testOnOffMotionDetectionChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkAccount blinkAccount = BlinkTestUtil.testBlinkAccount();
        doReturn(blinkAccount).when(accountHandler).getBlinkAccount();
        CameraService cameraService = mock(CameraService.class);
        cameraHandler.cameraService = cameraService;
        doReturn(123L).when(cameraService).motionDetection(ArgumentMatchers.any(BlinkAccount.class),
                ArgumentMatchers.any(CameraConfiguration.class), anyBoolean());
        cameraHandler.handleCommand(CHANNEL_CAMERA_MOTIONDETECTION, OnOffType.ON);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(cameraService).motionDetection(blinkAccount, config, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Boolean>> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(cameraService).watchCommandStatus(any(), same(blinkAccount), any(), any(), handlerCaptor.capture());
        handlerCaptor.getValue().accept(true);
        // check if correct handler is called (bug in commit 767f08f7)
        verify(callback, times(0)).stateUpdated(eq(CHANNEL_CAMERA_SETTHUMBNAIL), any());
        verify(accountHandler).getDevices(true);
    }

    @Test
    void testSetThumbnailChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkAccount blinkAccount = BlinkTestUtil.testBlinkAccount();
        doReturn(blinkAccount).when(accountHandler).getBlinkAccount();
        CameraService cameraService = mock(CameraService.class);
        cameraHandler.cameraService = cameraService;
        doReturn(123L).when(cameraService).createThumbnail(ArgumentMatchers.any(BlinkAccount.class),
                ArgumentMatchers.any(CameraConfiguration.class));
        cameraHandler.handleCommand(CHANNEL_CAMERA_SETTHUMBNAIL, OnOffType.ON);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(cameraService).createThumbnail(blinkAccount, config);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Boolean>> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(cameraService).watchCommandStatus(any(), same(blinkAccount), any(), any(), handlerCaptor.capture());
        handlerCaptor.getValue().accept(true);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_SETTHUMBNAIL), eq(OnOffType.OFF));
        verify(accountHandler).getDevices(true);
    }

    @Test
    void testGetThumbnailChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkAccount blinkAccount = BlinkTestUtil.testBlinkAccount();
        doReturn(blinkAccount).when(accountHandler).getBlinkAccount();
        CameraService cameraService = mock(CameraService.class);
        cameraHandler.cameraService = cameraService;
        BlinkCamera camera = new BlinkCamera(123L, 234L);
        camera.thumbnail = "/full/path/to/thumbnail.jpg";
        doReturn(camera).when(accountHandler).getCameraState(ArgumentMatchers.any(CameraConfiguration.class),
                eq(false));
        byte[] bytes = "expected".getBytes(StandardCharsets.UTF_8);
        RawType expected = new RawType(bytes, "image/jpeg");
        doReturn(bytes).when(cameraService).getThumbnail(ArgumentMatchers.any(BlinkAccount.class), anyString());
        cameraHandler.handleCommand(CHANNEL_CAMERA_GETTHUMBNAIL, RefreshType.REFRESH);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(accountHandler).getCameraState(config, false);
        verify(cameraService).getThumbnail(blinkAccount, camera.thumbnail);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_GETTHUMBNAIL), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(expected));
    }

    @Test
    void testDispose() {
        cameraHandler.cameraService = cameraService;
        cameraHandler.dispose();
        verify(cameraService).dispose();
    }

    @Test
    void testHandleHomescreenUpdate() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        cameraHandler.cameraService = cameraService;
        doReturn(BlinkTestUtil.testBlinkAccount()).when(accountHandler).getBlinkAccount();
        double temperature = 22.0;
        OnOffType battery = OnOffType.ON;
        OnOffType motionDetection = OnOffType.OFF;
        BlinkCamera camera = new BlinkCamera(123L, 234L);
        camera.thumbnail = "thumbnail";
        byte[] thumbnail = new byte[0];
        doReturn(temperature).when(accountHandler).getTemperature(any());
        doReturn(battery).when(accountHandler).getBattery(any());
        doReturn(motionDetection).when(accountHandler).getMotionDetection(any(), anyBoolean());
        doReturn(camera).when(accountHandler).getCameraState(any(), anyBoolean());
        doReturn(thumbnail).when(cameraService).getThumbnail(any(), any());
        cameraHandler.handleHomescreenUpdate();
        verify(callback).stateUpdated(CHANNEL_CAMERA_TEMPERATURE,
                new QuantityType<>(temperature, ImperialUnits.FAHRENHEIT));
        verify(callback).stateUpdated(CHANNEL_CAMERA_BATTERY, battery);
        verify(callback).stateUpdated(CHANNEL_CAMERA_MOTIONDETECTION, motionDetection);
        verify(accountHandler).getCameraState(any(), eq(false));
        verify(callback).stateUpdated(CHANNEL_CAMERA_GETTHUMBNAIL, new RawType(thumbnail, "image/jpeg"));
    }

    @Test
    void testHandleHomescreenUpdateOnException() throws IOException {
        cameraHandler.cameraService = cameraService;
        doThrow(IOException.class).when(accountHandler).getTemperature(any());
        cameraHandler.handleHomescreenUpdate();
        verify(accountHandler).setOffline(any(IOException.class));
        assertThat(cameraHandler.lastThumbnailPath, is(emptyString()));
    }
}
