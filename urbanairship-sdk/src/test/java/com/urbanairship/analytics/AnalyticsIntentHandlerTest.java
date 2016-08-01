/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.analytics;

import com.urbanairship.BaseTestCase;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.TestApplication;
import com.urbanairship.UAirship;
import com.urbanairship.job.Job;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.push.PushManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.mockito.internal.verification.Times;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static junit.framework.Assert.assertEquals;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AnalyticsIntentHandlerTest extends BaseTestCase {

    AnalyticsIntentHandler intentHandler;
    EventApiClient mockClient;
    EventDataManager mockDataManager;
    PushManager mockPushManager;
    Analytics mockAnalytics;
    JobDispatcher mockDispatcher;

    String channelId;
    PreferenceDataStore dataStore;

    @Before
    public void setUp() {
        mockDispatcher = mock(JobDispatcher.class);
        mockPushManager = mock(PushManager.class);
        mockDataManager = mock(EventDataManager.class);
        mockAnalytics = mock(Analytics.class);
        mockClient = mock(EventApiClient.class);

        Mockito.when(mockPushManager.getChannelId()).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) throws Throwable {
                return channelId;
            }
        });

        TestApplication.getApplication().setAnalytics(mockAnalytics);
        TestApplication.getApplication().setPushManager(mockPushManager);

        channelId = "some channel ID";
        dataStore = TestApplication.getApplication().preferenceDataStore;

        intentHandler = new AnalyticsIntentHandler(TestApplication.getApplication(), UAirship.shared(),
                dataStore, mockDispatcher, mockDataManager, mockClient);
    }

    /**
     * Tests adding an event from an intent passed the next send time adds the event and schedules
     * a send in 10 seconds.
     */
    @Test
    public void testAddEventAfterNextSendTime() {
        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_ADD)
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_TYPE, "some-type")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_ID, "event id")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_TIME_STAMP, "100")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_DATA, "DATA!")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_SESSION_ID, "session id")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_PRIORITY, Event.NORMAL_PRIORITY)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Verify we add an event.
        Mockito.verify(mockDataManager, new Times(1)).insertEvent("some-type", "DATA!", "event id", "session id", "100");

        // Check it schedules an upload
        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<Job>() {
            @Override
            public boolean matches(Object argument) {
                Job job = (Job) argument;
                return job.getAction().equals(AnalyticsIntentHandler.ACTION_SEND);
            }
        }), eq(10000L), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * Tests adding an event from an intent passed the next send time adds the event and schedules
     * a send for the next send time.
     */
    @Test
    public void testAddEventBeforeNextSendTime() {
        // Set the last send time to the current time so the next send time is minBatchInterval
        dataStore.put(AnalyticsIntentHandler.LAST_SEND_KEY, System.currentTimeMillis());

        // Set the minBatchInterval to 20 seconds
        dataStore.put(AnalyticsIntentHandler.MIN_BATCH_INTERVAL_KEY, 20000);

        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_ADD)
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_TYPE, "some-type")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_ID, "event id")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_TIME_STAMP, "100")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_DATA, "DATA!")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_SESSION_ID, "session id")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_PRIORITY, Event.NORMAL_PRIORITY)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Check it schedules an upload
        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<Job>() {
            @Override
            public boolean matches(Object argument) {
                Job job = (Job) argument;
                return job.getAction().equals(AnalyticsIntentHandler.ACTION_SEND);
            }
        }), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * Tests adding an event from intent no-ops when the event data is empty.
     */
    @Test
    public void testAddEventEmptyData() {
        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_ADD)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Verify we don't add any events.
        Mockito.verify(mockDataManager, new Times(0)).insertEvent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Tests sending events
     */
    @Test
    public void testSendingEvents() {
        Map<String, String> events = new HashMap<>();
        events.put("firstEvent", "{ 'firstEventBody' }");

        // Set up data manager to return 2 count for events.
        // Note: we only have one event, but it should only ask for one to upload
        // having it return 2 will make it schedule to upload events in the future
        when(mockDataManager.getEventCount()).thenReturn(2);

        // Return 200 bytes in size.  It should only be able to do 100 bytes so only
        // the first event.
        when(mockDataManager.getDatabaseSize()).thenReturn(200);

        // Return the event when it asks for 1
        when(mockDataManager.getEvents(1)).thenReturn(events);

        // Set the max batch size to 100
        dataStore.put(AnalyticsIntentHandler.MAX_BATCH_SIZE_KEY, 100);

        // Set up the response
        EventResponse response = mock(EventResponse.class);
        when(response.getStatus()).thenReturn(200);
        when(response.getMaxTotalSize()).thenReturn(200);
        when(response.getMaxBatchSize()).thenReturn(300);
        when(response.getMaxWait()).thenReturn(400);
        when(response.getMinBatchInterval()).thenReturn(100);

        // Return the response
        when(mockClient.sendEvents(UAirship.shared(), events.values())).thenReturn(response);

        // Start the upload process
        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_SEND)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Check mockClients receives the events
        Mockito.verify(mockClient).sendEvents(UAirship.shared(), events.values());

        // Check data manager deletes events
        Mockito.verify(mockDataManager).deleteEvents(events.keySet());

        // Verify responses are being saved
        assertEquals(200, dataStore.getInt(AnalyticsIntentHandler.MAX_TOTAL_DB_SIZE_KEY, 0));
        assertEquals(300, dataStore.getInt(AnalyticsIntentHandler.MAX_BATCH_SIZE_KEY, 0));
        assertEquals(400, dataStore.getInt(AnalyticsIntentHandler.MAX_WAIT_KEY, 0));
        assertEquals(100, dataStore.getInt(AnalyticsIntentHandler.MIN_BATCH_INTERVAL_KEY, 0));

        // Check it schedules an upload
        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<Job>() {
            @Override
            public boolean matches(Object argument) {
                Job job = (Job) argument;
                return job.getAction().equals(AnalyticsIntentHandler.ACTION_SEND);
            }
        }), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * Test event batching only sends a max of 500 events.
     */
    @Test
    public void testSendEventMaxCount() {
        Map<String, String> events = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            events.put("event " + i, "{ 'body' }");
        }

        dataStore.put(AnalyticsIntentHandler.MAX_BATCH_SIZE_KEY, 100000);

        when(mockDataManager.getDatabaseSize()).thenReturn(100000);
        when(mockDataManager.getEventCount()).thenReturn(1000);

        // Return the event when it asks for 1
        when(mockDataManager.getEvents(500)).thenReturn(events);

        // Set up the response
        EventResponse response = mock(EventResponse.class);
        when(response.getStatus()).thenReturn(200);
        when(mockClient.sendEvents(UAirship.shared(), events.values())).thenReturn(response);

        // Start the upload process
        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_SEND)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Check mockClients receives the events
        Mockito.verify(mockClient).sendEvents(UAirship.shared(), events.values());

        // Check data manager deletes events
        Mockito.verify(mockDataManager).deleteEvents(events.keySet());
    }

    /**
     * Test sending events when there's no channel ID present
     */
    @Test
    public void testSendingWithNoChannelID() {
        // Return null when channel ID is expected
        channelId = null;

        Map<String, String> events = new HashMap<>();
        events.put("firstEvent", "{ 'firstEventBody' }");

        // Satisfy event count check to avoid early return.
        when(mockDataManager.getEventCount()).thenReturn(1);
        // Return the event when it asks for 1
        when(mockDataManager.getEvents(1)).thenReturn(events);

        // Start the upload process
        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_SEND)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Verify uploadEvents returns early when no channel ID is present.
        Mockito.verify(mockClient, never()).sendEvents(UAirship.shared(), events.values());
    }

    /**
     * Test sending events when the upload fails
     */
    @Test
    public void testSendEventsFails() {
        Map<String, String> events = new HashMap<>();
        events.put("firstEvent", "{ 'firstEventBody' }");
        when(mockDataManager.getEventCount()).thenReturn(1);
        when(mockDataManager.getDatabaseSize()).thenReturn(100);
        when(mockDataManager.getEvents(1)).thenReturn(events);

        dataStore.put(AnalyticsIntentHandler.MAX_BATCH_SIZE_KEY, 100);


        // Return a null response
        when(mockClient.sendEvents(UAirship.shared(), events.values())).thenReturn(null);

        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_SEND)
                     .build();

        assertEquals(Job.JOB_RETRY, intentHandler.performJob(job));

        Mockito.verify(mockClient).sendEvents(UAirship.shared(), events.values());

        // If it fails, it should skip deleting events
        Mockito.verify(mockDataManager, Mockito.never()).deleteEvents(events.keySet());
    }

    /**
     * Test adding a region event results in a scheduled alarm
     */
    @Test
    public void testAddingHighPriorityEvents() {
        // Set last send time to year 3005 so we don't upload immediately
        dataStore.put(AnalyticsIntentHandler.LAST_SEND_KEY, 32661446400000L);

        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_ADD)
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_TYPE, "some-type")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_ID, "event id")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_TIME_STAMP, "100")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_DATA, "DATA!")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_SESSION_ID, "session id")
                     .putExtra(AnalyticsIntentHandler.EXTRA_EVENT_PRIORITY, Event.HIGH_PRIORITY)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));

        // Check it schedules an upload
        verify(mockDispatcher).dispatch(Mockito.argThat(new ArgumentMatcher<Job>() {
            @Override
            public boolean matches(Object argument) {
                Job job = (Job) argument;
                return job.getAction().equals(AnalyticsIntentHandler.ACTION_SEND);
            }
        }), eq(1000L), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * Test DELETE_ALL intent action deletes all events.
     */
    @Test
    public void testDeleteAll() {
        Job job = Job.newBuilder(AnalyticsIntentHandler.ACTION_DELETE_ALL)
                     .build();

        assertEquals(Job.JOB_FINISHED, intentHandler.performJob(job));
        Mockito.verify(mockDataManager).deleteAllEvents();
    }
}
