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

import static org.openhab.binding.blink.internal.BlinkBindingConstants.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.blink.internal.config.CameraConfiguration;
import org.openhab.binding.blink.internal.service.CameraService;
import org.openhab.binding.blink.internal.servlet.ThumbnailServlet;
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
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link CameraHandler} is responsible for initializing camera thing and handling commands, which are
 * sent to one of the camera's channels.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
@NonNullByDefault
public class CameraHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(CameraHandler.class);

    @NonNullByDefault({})
    CameraConfiguration config;
    @NonNullByDefault({})
    AccountHandler accountHandler;
    private final HttpService httpService;
    private final NetworkAddressService networkAddressService;
    CameraService cameraService;

    @Nullable
    ThumbnailServlet thumbnailServlet;

    @Nullable
    ScheduledFuture<?> pollStateJob;

    String lastThumbnailPath = "";

    public CameraHandler(Thing thing, HttpService httpService, NetworkAddressService networkAddressService,
            HttpClientFactory httpClientFactory, Gson gson) {
        super(thing);
        this.httpService = httpService;
        this.networkAddressService = networkAddressService;
        this.cameraService = new CameraService(httpClientFactory.getCommonHttpClient(), gson);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        try {
            if (CHANNEL_CAMERA_TEMPERATURE.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    double temp = accountHandler.getTemperature(config);
                    updateState(CHANNEL_CAMERA_TEMPERATURE, new QuantityType<>(temp, ImperialUnits.FAHRENHEIT));
                }
            } else if (CHANNEL_CAMERA_BATTERY.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_BATTERY, accountHandler.getBattery(config));
                }
            } else if (CHANNEL_CAMERA_MOTIONDETECTION.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_MOTIONDETECTION, accountHandler.getMotionDetection(config, false));
                } else if (command instanceof OnOffType) {
                    OnOffType cmd = (OnOffType) command;
                    boolean enable = (cmd == OnOffType.ON);
                    Long cmdId = cameraService.motionDetection(accountHandler.getBlinkAccount(), config, enable);
                    cameraService.watchCommandStatus(scheduler, accountHandler.getBlinkAccount(), config.networkId,
                            cmdId, this::asyncCommandFinished);
                }
            } else if (CHANNEL_CAMERA_SETTHUMBNAIL.equals(channelUID.getId())) {
                if (command instanceof RefreshType)
                    updateState(CHANNEL_CAMERA_SETTHUMBNAIL, OnOffType.OFF);
                if (command == OnOffType.ON) {
                    Long cmdId = cameraService.createThumbnail(accountHandler.getBlinkAccount(), config);
                    cameraService.watchCommandStatus(scheduler, accountHandler.getBlinkAccount(), config.networkId,
                            cmdId, this::setThumbnailFinished);
                }
            } else if (CHANNEL_CAMERA_GETTHUMBNAIL.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    String imagePath = accountHandler.getCameraState(config, true).thumbnail;
                    lastThumbnailPath = imagePath;
                    updateState(CHANNEL_CAMERA_GETTHUMBNAIL, new RawType(
                            cameraService.getThumbnail(accountHandler.getBlinkAccount(), imagePath), "image/jpeg"));
                }
            }
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    public byte[] getThumbnail() throws IOException {
        String imagePath = accountHandler.getCameraState(config, true).thumbnail;
        return cameraService.getThumbnail(accountHandler.getBlinkAccount(), imagePath);
    }

    @Override
    public void initialize() {
        config = getConfigAs(CameraConfiguration.class);

        @Nullable
        Bridge bridge = getBridge();
        if (bridge == null || bridge.getHandler() == null) {
            logger.warn("Cannot handle commands of blink things without a bridge: {}", thing.getUID().getAsString());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "no bridge");
            return;
        }
        accountHandler = (AccountHandler) bridge.getHandler();

        if (thumbnailServlet == null) {
            try {
                thumbnailServlet = new ThumbnailServlet(httpService, this);
                Map<String, String> properties = editProperties();
                String scheme = "http://";
                int port = 8080;
                String imageUrl = scheme + networkAddressService.getPrimaryIpv4HostAddress() + ":" + port
                        + "/blink/thumbnail/" + thing.getUID().getId();
                properties.put("thumbnail", imageUrl);
                updateProperties(properties);
            } catch (IllegalStateException e) {
                logger.warn("Failed to create account servlet", e);
            }
        }

        if (pollStateJob == null || pollStateJob.isCancelled()) {
            pollStateJob = scheduler.scheduleWithFixedDelay(this::updateCameraState, 20, 5, TimeUnit.SECONDS);
        }
        updateStatus(ThingStatus.ONLINE);
    }

    public void updateCameraState() {
        try {
            updateState(CHANNEL_CAMERA_TEMPERATURE,
                    new QuantityType<>(accountHandler.getTemperature(config), ImperialUnits.FAHRENHEIT));
            updateState(CHANNEL_CAMERA_BATTERY, accountHandler.getBattery(config));
            updateState(CHANNEL_CAMERA_MOTIONDETECTION, accountHandler.getMotionDetection(config, false));
            String imagePath = accountHandler.getCameraState(config, false).thumbnail;
            if (!lastThumbnailPath.equals(imagePath)) {
                logger.debug("Loading NEW thumbnail during refresh");
                lastThumbnailPath = imagePath;
                updateState(CHANNEL_CAMERA_GETTHUMBNAIL, new RawType(
                        cameraService.getThumbnail(accountHandler.getBlinkAccount(), imagePath), "image/jpeg"));
            }
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    @Override
    public void dispose() {
        if (pollStateJob != null)
            pollStateJob.cancel(true);
        cameraService.dispose();
        super.dispose();
    }

    private void setThumbnailFinished(boolean success) {
        updateState(CHANNEL_CAMERA_SETTHUMBNAIL, OnOffType.OFF);
        asyncCommandFinished(success);
    }

    private void asyncCommandFinished(boolean success) {
        if (success) {
            accountHandler.getDevices(true); // trigger refresh of homescreen
            updateCameraState();
        }
    }
}
