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
import org.openqa.selenium.By;
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
import static org.mockito.Mockito.*;

class JSErrorLinkTraversalTest {
    private static final String SOURCE = "http://localhost:9091/server/Server%201";
    private static final String FIRST = "http://localhost:9091/players";
    private static final String SECOND = "http://localhost:9091/network";
    private final ChromeDriver driver = mock(ChromeDriver.class);
    private final Logs logs = mock(Logs.class);
    private final AtomicReference<String> currentPage = new AtomicReference<>();

    @BeforeEach
    void prepareBrowser() {
        WebDriver.Options options = mock(WebDriver.Options.class);
        when(driver.manage()).thenReturn(options);
        when(options.logs()).thenReturn(logs);
        when(logs.get(LogType.BROWSER)).thenReturn(new LogEntries(List.of()));
        when(driver.executeScript("return document.readyState")).thenReturn("complete");
        WebElement loadedPage = mock(WebElement.class);
        when(loadedPage.isDisplayed()).thenReturn(true);
        when(driver.findElement(By.className("load-in"))).thenReturn(loadedPage);
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

        var navigation = inOrder(driver);
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
}
