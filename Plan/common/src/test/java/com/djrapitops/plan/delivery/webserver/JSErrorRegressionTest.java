/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.delivery.webserver;

import com.djrapitops.plan.PlanSystem;
import com.djrapitops.plan.component.Component;
import com.djrapitops.plan.component.ComponentService;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.annotation.ComponentProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.gathering.domain.DataMap;
import com.djrapitops.plan.gathering.domain.FinishedSession;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.settings.config.paths.WebserverSettings;
import com.djrapitops.plan.settings.config.paths.DisplaySettings;
import com.djrapitops.plan.settings.locale.Locale;
import com.djrapitops.plan.storage.database.DBSystem;
import com.djrapitops.plan.storage.database.Database;
import com.djrapitops.plan.storage.database.queries.DataStoreQueries;
import com.djrapitops.plan.storage.database.transactions.events.PlayerRegisterTransaction;
import com.djrapitops.plan.storage.database.transactions.events.StoreSessionTransaction;
import com.djrapitops.plan.storage.database.transactions.events.StoreWorldNameTransaction;
import extension.FullSystemExtension;
import extension.SeleniumExtension;
import org.awaitility.Awaitility;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.logging.LogType;
import utilities.RandomData;
import utilities.TestConstants;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.djrapitops.plan.delivery.export.ExportTestUtilities.assertNoLogs;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test class is for catching any JavaScript errors.
 * <p>
 * Errors may have been caused by:
 * - Javascript mistakes / build issues
 * - Missed console.log statements
 * - Missing file definition in Mocker
 */
@ExtendWith({FullSystemExtension.class, SeleniumExtension.class})
class JSErrorRegressionTest {

    private static final int TEST_PORT_NUMBER = 9091;

    @BeforeAll
    static void setUpClass(PlanSystem system) {
        system.getConfigSystem().getConfig()
                .set(WebserverSettings.PORT, TEST_PORT_NUMBER)
                .set(DisplaySettings.GRAPH_TPS_THRESHOLD_MED, 10.0);
        system.enable();
        savePlayerData(system);
        saveServerData(system);
    }

    private static void savePlayerData(PlanSystem system) {
        ServerUUID serverUUID = system.getServerInfo().getServerUUID();
        DBSystem dbSystem = system.getDatabaseSystem();
        Database database = dbSystem.getDatabase();
        UUID uuid = TestConstants.PLAYER_ONE_UUID;
        database.executeTransaction(new PlayerRegisterTransaction(uuid, RandomData::randomTime, TestConstants.PLAYER_ONE_NAME));
        FinishedSession session = new FinishedSession(uuid, serverUUID, 1000L, 11000L, 500L, new DataMap());
        database.executeTransaction(new StoreWorldNameTransaction(serverUUID, "world"));
        database.executeTransaction(new StoreSessionTransaction(session));
    }

    private static void saveServerData(PlanSystem system) {
        Database database = system.getDatabaseSystem().getDatabase();
        ServerUUID serverUUID = system.getServerInfo().getServerUUID();
        database.executeInTransaction(
                DataStoreQueries.storeTPS(serverUUID,
                        SparsePerformanceFixture.recentSample(System.currentTimeMillis()))).join();
    }

    @AfterAll
    static void tearDownClass(PlanSystem system) {
        if (system != null) {
            system.disable();
        }
    }

    @AfterEach
    void tearDownTest(WebDriver driver) {
        SeleniumExtension.newTab(driver);
    }

    @TestFactory
    Collection<DynamicTest> javascriptRegressionTest(ChromeDriver driver, PlanSystem system) {
        String[] addresses = new String[]{
                "http://localhost:" + TEST_PORT_NUMBER + "/player/" + TestConstants.PLAYER_ONE_NAME,
                "http://localhost:" + TEST_PORT_NUMBER + "/player/" + TestConstants.PLAYER_ONE_UUID_STRING,
                "http://localhost:" + TEST_PORT_NUMBER + "/network",
                "http://localhost:" + TEST_PORT_NUMBER + "/server/Server 1",
                "http://localhost:" + TEST_PORT_NUMBER + "/players",
                "http://localhost:" + TEST_PORT_NUMBER + "/query"
        };

        return Arrays.stream(addresses)
                .map(link -> testAddress(link, driver, system))
                .toList();
    }

    @TestFactory
    Stream<DynamicTest> componentJsRegressionTest(ChromeDriver driver, PlanSystem system) {
        system.getApiServices().getExtensionService()
                .register(new ComponentExtension())
                .orElseThrow(AssertionError::new)
                .updatePlayerData(TestConstants.PLAYER_ONE_UUID, TestConstants.PLAYER_ONE_NAME);

        String address = "http://localhost:" + TEST_PORT_NUMBER + "/player/" + TestConstants.PLAYER_ONE_UUID_STRING + "/plugins/" + system.getServerInfo().getServerIdentifier().getName().replaceAll(" ", "%20");
        return Stream.of(testAddress(address, driver, system));
    }

    private DynamicTest testAddress(String address, ChromeDriver driver, PlanSystem system) {
        return DynamicTest.dynamicTest("Page should not log anything on js console: " + address, () -> {
            Locale locale = system.getLocaleSystem().getLocale();
            try {
                driver.get(address);
                SeleniumExtension.waitForPageLoadForSeconds(5, driver);
                SeleniumExtension.waitForElementToBeVisible(By.className("load-in"), driver);
                assertNoUnexpectedLogs(driver, address);
            } finally {
                locale.clear(); // Reset locale after test
            }
        });
    }

    @DisplayName("Links on page function: ")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "http://localhost:" + TEST_PORT_NUMBER + "/player/" + TestConstants.PLAYER_ONE_NAME,
            "http://localhost:" + TEST_PORT_NUMBER + "/player/" + TestConstants.PLAYER_ONE_UUID_STRING,
            "http://localhost:" + TEST_PORT_NUMBER + "/network",
            "http://localhost:" + TEST_PORT_NUMBER + "/server/Server 1",
            "http://localhost:" + TEST_PORT_NUMBER + "/players",
            "http://localhost:" + TEST_PORT_NUMBER + "/query"
    })
    void linkFunctionRegressionTest(String address, ChromeDriver driver) {
        // The responsive sidebar is not mounted below 1350px, including Chrome's
        // default headless viewport. Exercise the actual desktop navigation.
        driver.manage().window().setSize(new Dimension(1600, 1000));
        driver.get(address);
        SeleniumExtension.waitForElementToBeVisible(By.className("load-in"), driver);
        SeleniumExtension.waitForElementToBeVisible(By.id("accordionSidebar"), driver);

        List<String> anchorLinks = getLinks(driver, 0);
        assertFalse(anchorLinks.isEmpty(), () -> "No internal links found at " + address);

        for (String href : anchorLinks) {
            driver.get(href);
            SeleniumExtension.waitForElementToBeVisible(By.className("load-in"), driver);

            assertNoUnexpectedLogs(driver, "Page link '" + address + "'->'" + href + "'");
            System.out.println("'" + address + "' has link to " + href);
        }
    }

    @Test
    void sparsePerformanceHistoryDisplaysUnavailableValues(ChromeDriver driver) {
        driver.manage().window().setSize(new Dimension(1600, 1000));
        String address = "http://localhost:" + TEST_PORT_NUMBER + "/server/Server%201/performance";
        driver.get(address);
        SeleniumExtension.waitForElementToBeVisible(By.id("performance-as-numbers"), driver);
        Object response = driver.executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch(arguments[0]).then(async response => done({status: response.status, body: await response.json()}))
                    .catch(error => done({error: String(error)}));
                """, "http://localhost:" + TEST_PORT_NUMBER
                + "/v1/datapoint?type=MSPT_MAX_95TH&afterMillisAgo=86400000&server=Server%201");
        assertTrue(response instanceof Map<?, ?>, "Recent MSPT request must return a response");
        Map<?, ?> result = (Map<?, ?>) response;
        assertEquals(200L, result.get("status"), "Recent ordinary MSPT maximum must not become no-data");
        assertEquals(80.0, ((Number) ((Map<?, ?>) result.get("body")).get("value")).doubleValue());
        Object missingIdle = driver.executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch(arguments[0]).then(response => done(response.status)).catch(error => done(String(error)));
                """, "http://localhost:" + TEST_PORT_NUMBER
                + "/v1/datapoint?type=CPU_IMPACT_PER_PLAYER&afterMillisAgo=86400000&server=Server%201");
        assertEquals(404L, missingIdle, "CPU impact must be unavailable without an observed idle baseline");
        Object missingLowTpsAverage = driver.executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch(arguments[0]).then(async response => done({status: response.status, body: await response.text()}))
                    .catch(error => done({error: String(error)}));
                """, "http://localhost:" + TEST_PORT_NUMBER
                + "/v1/datapoint?type=MSPT_AVERAGE_LOW_TPS&afterMillisAgo=86400000&server=Server%201");
        assertTrue(missingLowTpsAverage instanceof Map<?, ?>, "Low-TPS average request must return a response");
        Map<?, ?> missingLowTpsResult = (Map<?, ?>) missingLowTpsAverage;
        assertEquals(404L, missingLowTpsResult.get("status"),
                () -> "Low-TPS average must be unavailable without a qualifying sample: " + missingLowTpsResult.get("body"));
        Awaitility.await("sparse disk history renders '-' and recent numeric values")
                .atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                    List<WebElement> rows = driver.findElements(By.xpath(
                            "//*[@id='performance-as-numbers']//tr[td//*[contains(@class,'col-disk')]]"));
                    assertEquals(2, rows.size(), "Both minimum and maximum free-disk rows must be present");
                    for (WebElement row : rows) {
                        List<WebElement> cells = row.findElements(By.tagName("td"));
                        assertEquals(7, cells.size());
                        for (int historical : new int[]{2, 3, 4}) {
                            assertEquals("-", cells.get(historical).getText());
                        }
                        for (int recent : new int[]{1, 5, 6}) {
                            assertTrue(cells.get(recent).getText().matches("8(?:\\.0+)? GB"),
                                    "The populated recent 8000 MB disk sample must render 8 GB");
                        }
                    }
                    List<WebElement> cpuImpactRows = driver.findElements(By.xpath(
                            "//*[@id='performance-as-numbers']//tr[td//*[contains(@class,'col-cpu') and @data-icon='users']]"));
                    assertEquals(1, cpuImpactRows.size(), "The CPU impact row must be present");
                    List<WebElement> cpuCells = cpuImpactRows.getFirst().findElements(By.tagName("td"));
                    assertEquals(7, cpuCells.size());
                    for (int column = 1; column < cpuCells.size(); column++) {
                        assertEquals("-", cpuCells.get(column).getText(), "No historical or recent idle baseline is available");
                    }
                    List<WebElement> lowTpsMsptRows = driver.findElements(By.xpath(
                            "//*[@id='performance-as-numbers']//tr[td//*[contains(@class,'col-tps-low-spikes') and @data-icon='stopwatch']]"));
                    assertEquals(2, lowTpsMsptRows.size(), "Both low-TPS average and maximum rows must be present");
                    for (WebElement row : lowTpsMsptRows) {
                        List<WebElement> cells = row.findElements(By.tagName("td"));
                        assertEquals(7, cells.size());
                        for (int column = 1; column < cells.size(); column++) {
                            assertEquals("-", cells.get(column).getText(), "No window contains a qualifying low-TPS sample");
                        }
                    }
                });
        assertNoUnexpectedLogs(driver, address);
    }

    private static void assertNoUnexpectedLogs(ChromeDriver driver, String address) {
        // The API intentionally returns 404 for absent samples; the table renders
        // '-'. Permit only this fixture's exact empty combinations, whose visible
        // fallback is also checked above. Other HTTP errors and JS errors fail.
        assertNoLogs(driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                .filter(entry -> !SparsePerformanceFixture.isExpectedMissingMetric(entry)).toList(), address);
    }

    private List<String> getLinks(ChromeDriver driver, int attempt) {
        if (attempt >= 5) return Collections.emptyList();

        try {
            return driver.findElements(By.tagName("a")).stream()
                    .map(anchorLink -> anchorLink.getAttribute("href"))
                    .filter(Objects::nonNull)
                    .filter(href -> href.contains("localhost") && !href.contains("logout"))
                    .map(href -> href.split("#")[0])
                    .distinct()
                    .toList();
        } catch (StaleElementReferenceException _) {
            return getLinks(driver, attempt + 1);
        }
    }

    @PluginInfo(name = "Component-regression")
    static class ComponentExtension implements DataExtension {
        @ComponentProvider(text = "regression")
        public Component component(UUID playerUUID) {
            @Language("JSON")
            String json = """
                    {
                      "extra": [
                        {
                          "color": "gray",
                          "extra": [
                            {
                              "color": "#9D50BB",
                              "extra": [
                                {
                                  "color": "#914EB7",
                                  "extra": [
                                    {
                                      "color": "#864CB3",
                                      "extra": [
                                        {
                                          "color": "#7A4AAE",
                                          "extra": [
                                            {
                                              "color": "#6E48AA",
                                              "extra": [
                                                {
                                                  "color": "gray",
                                                  "extra": [
                                                    {
                                                      "color": "dark_aqua",
                                                      "text": "Executive Advisor"
                                                    }
                                                  ],
                                                  "text": "] "
                                                }
                                              ],
                                              "text": "f"
                                            }
                                          ],
                                          "text": "f"
                                        }
                                      ],
                                      "text": "a"
                                    }
                                  ],
                                  "text": "t"
                                }
                              ],
                              "text": "S"
                            }
                          ],
                          "text": "["
                        }
                      ],
                      "text": " "
                    }""";
            return ComponentService.getInstance().fromJson(json);
        }

        @ComponentProvider(text = "regression2")
        public Component component2(UUID playerUUID) {
            @Language("JSON")
            String json2 = """
                    {"color":"dark_aqua","text":"EA"}""";
            return ComponentService.getInstance().fromJson(json2);
        }
    }
}
