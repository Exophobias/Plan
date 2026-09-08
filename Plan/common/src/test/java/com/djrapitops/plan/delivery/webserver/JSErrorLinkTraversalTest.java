/*
 * This file is part of Player Analytics (Plan).
 *
 * Plan is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or any later version.
 */
package com.djrapitops.plan.delivery.webserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.Logs;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class JSErrorLinkTraversalTest {
    private static final String SOURCE = "http://localhost:9091/server/Server%201";
    private static final String FIRST = "http://localhost:9091/players";
    private static final String SECOND = "http://localhost:9091/network";
    private final ChromeDriver driver = mock(ChromeDriver.class);
    private final Logs logs = mock(Logs.class);
    private final WebDriver.Window window = mock(WebDriver.Window.class);
    private final AtomicReference<String> currentPage = new AtomicReference<>();

    @BeforeEach
    void prepareBrowser() {
        WebDriver.Options options = mock(WebDriver.Options.class);
        when(driver.manage()).thenReturn(options);
        when(options.logs()).thenReturn(logs);
        when(options.window()).thenReturn(window);
        when(logs.get(LogType.BROWSER)).thenReturn(new LogEntries(List.of()));
        when(driver.executeScript("return document.readyState")).thenReturn("complete");
        WebElement loadedPage = mock(WebElement.class);
        when(loadedPage.isDisplayed()).thenReturn(true);
        when(driver.findElement(By.className("load-in"))).thenReturn(loadedPage);
        when(driver.findElement(By.id("accordionSidebar"))).thenReturn(loadedPage);
        doAnswer(call -> {
            currentPage.set(call.getArgument(0));
            return null;
        }).when(driver).get(anyString());
    }

    private WebElement link(String address) {
        WebElement anchor = mock(WebElement.class);
        when(anchor.getAttribute("href")).thenReturn(address);
        return anchor;
    }

    @Test
    void navigatesEveryDestinationInsteadOfReloadingTheSource() {
        List<WebElement> links = List.of(link(FIRST), link(SECOND));
        when(driver.findElements(By.tagName("a"))).thenReturn(links);

        new JSErrorRegressionTest().linkFunctionRegressionTest(SOURCE, driver);

        var navigation = inOrder(window, driver);
        navigation.verify(window).setSize(new Dimension(1600, 1000));
        navigation.verify(driver).get(SOURCE);
        navigation.verify(driver).get(FIRST);
        navigation.verify(driver).get(SECOND);
        verify(driver, times(1)).get(SOURCE);
    }

    @Test
    void anEmptyLinkDiscoveryCannotPassWithoutCheckingDestinations() {
        when(driver.findElements(By.tagName("a"))).thenReturn(List.of());

        AssertionError failure = assertThrows(AssertionError.class,
                () -> new JSErrorRegressionTest().linkFunctionRegressionTest(SOURCE, driver));

        assertTrue(failure.getMessage().contains("No internal links found"));
    }

    @Test
    void reportsConsoleErrorsFromTheDestinationBeingVisited() {
        List<WebElement> links = List.of(link(FIRST), link(SECOND));
        when(driver.findElements(By.tagName("a"))).thenReturn(links);
        when(logs.get(LogType.BROWSER)).thenAnswer(call -> new LogEntries(SECOND.equals(currentPage.get())
                ? List.of(new LogEntry(Level.SEVERE, 1, "destination script failed")) : List.of()));

        AssertionError failure = assertThrows(AssertionError.class,
                () -> new JSErrorRegressionTest().linkFunctionRegressionTest(SOURCE, driver));

        assertTrue(failure.getMessage().contains(SECOND));
        assertTrue(failure.getMessage().contains("destination script failed"));
        verify(driver).get(SECOND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "type=DISK_MIN&afterMillisAgo=1209600000&beforeMillisAgo=604800000&server=Server%201",
            "server=Server%201&type=CPU_AVERAGE&activityType=IDLE&afterMillisAgo=86400000"
    })
    void permitsOnlyTheSparseFixturesExpectedEmptyResponses(String query) {
        assertTrue(SparsePerformanceFixture.isExpectedMissingMetric(resourceFailure("/v1/datapoint?" + query, 404)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "type=DISK_MIN&afterMillisAgo=1209600000&beforeMillisAgo=604800000&server=Other",
            "type=UNKNOWN&afterMillisAgo=1209600000&beforeMillisAgo=604800000&server=Server%201",
            "type=DISK_MIN&afterMillisAgo=86400000&server=Server%201",
            "type=CPU_AVERAGE&activityType=ACTIVE&afterMillisAgo=86400000&server=Server%201",
            "type=DISK_MIN&afterMillisAgo=1209600000&beforeMillisAgo=604800000&server=Server%201&server=Other",
            "type=DISK_MIN&afterMillisAgo=1209600000&beforeMillisAgo=1&server=Server%201",
            "type=DISK_MIN&afterMillisAgo=1209600000&beforeMillisAgo=604800000&server=Server%201&unexpected=1"
    })
    void unexpectedMetricServerWindowAndRecentData404sStillFail(String query) {
        assertFalse(SparsePerformanceFixture.isExpectedMissingMetric(resourceFailure("/v1/datapoint?" + query, 404)));
    }

    @Test
    void serverErrorsWrongRoutesAndJavascriptErrorsRemainFailures() {
        String path = "/v1/datapoint?type=DISK_MIN&afterMillisAgo=1209600000&beforeMillisAgo=604800000&server=Server%201";
        assertFalse(SparsePerformanceFixture.isExpectedMissingMetric(resourceFailure(path, 500)));
        assertFalse(SparsePerformanceFixture.isExpectedMissingMetric(resourceFailure(path.replace("datapoint", "missing-route"), 404)));
        assertFalse(SparsePerformanceFixture.isExpectedMissingMetric(new LogEntry(Level.SEVERE, 1,
                "Uncaught TypeError: " + resourceFailure(path, 404).getMessage())));
        assertFalse(SparsePerformanceFixture.isExpectedMissingMetric(new LogEntry(Level.SEVERE, 1,
                resourceFailure(path, 404).getMessage().replace("localhost:9091", "elsewhere:9091"))));
    }

    private LogEntry resourceFailure(String path, int status) {
        return new LogEntry(Level.SEVERE, 1, "http://localhost:9091" + path
                + " - Failed to load resource: the server responded with a status of " + status
                + (status == 404 ? " (Not Found)" : " (Internal Server Error)"));
    }
}
