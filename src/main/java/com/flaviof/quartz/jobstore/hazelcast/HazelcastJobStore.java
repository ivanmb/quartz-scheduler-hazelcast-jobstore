package com.flaviof.quartz.jobstore.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.ISet;
import com.hazelcast.core.MultiMap;
import com.hazelcast.query.Predicate;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.ACQUIRED;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.BLOCKED;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.NORMAL;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.PAUSED;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.STATE_COMPLETED;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.WAITING;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerState.toClassicTriggerState;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.quartz.Calendar;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.SchedulerConfigException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.StringMatcher;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static com.flaviof.quartz.jobstore.hazelcast.TriggerWrapper.newTriggerWrapper;
import com.hazelcast.core.Hazelcast;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author Flavio Ferreira
 *
 *         Thanks Antoine Méausoone for starting the work.
 */
public class HazelcastJobStore implements JobStore, Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(HazelcastJobStore.class);

  private static HazelcastInstance hazelcastClient;

  public static void setHazelcastClient(HazelcastInstance aHazelcastClient) {

    hazelcastClient = aHazelcastClient;
  }

  private final String HC_JOB_STORE_MAP_JOB = "job-store-map-job";
  private final String HC_JOB_STORE_MAP_JOB_BY_GROUP_MAP = "job-store-map-job-by-group-map";
  private final String HC_JOB_STORE_TRIGGER_BY_KEY_MAP = "job-store-trigger-by-key-map";
  private final String HC_JOB_STORE_TRIGGER_KEY_BY_GROUP_MAP = "job-trigger-key-by-group-map";
  private final String HC_JOB_STORE_PAUSED_TRIGGER_GROUPS = "job-paused-trigger-groups";
  private final String HC_JOB_STORE_PAUSED_JOB_GROUPS = "job-paused-job-groups";
  private final String HC_JOB_CALENDAR_MAP = "job-calendar-map";
  private final String HC_JOB_STORE_TRIGGERS_QUEUE = "job-triggers-queue";

  private SchedulerSignaler schedSignaler;

  private IMap<JobKey, JobDetail> jobsByKey;
  private IMap<TriggerKey, TriggerWrapper> triggersByKey;
  private BlockingQueue<TriggerKey> triggers;
  private MultiMap<String, JobKey> jobsByGroup;
  private MultiMap<String, TriggerKey> triggersByGroup;
  private IMap<String, Calendar> calendarsByName;
  private ISet<String> pausedTriggerGroups;
  private ISet<String> pausedJobGroups;

  private volatile boolean schedulerRunning = false;
  private long misfireThreshold = 5000;
  private String instanceName;
  protected String instanceId;

  public static final DateTimeFormatter FORMATTER = ISODateTimeFormat.basicDateTimeNoMillis();

  @Override
  public void initialize(ClassLoadHelper loadHelper, SchedulerSignaler signaler)
    throws SchedulerConfigException {

    LOG.debug("Initializing Hazelcast Job Store..");

    this.schedSignaler = signaler;

    if (hazelcastClient == null) {
      LOG.warn("Starting new local hazelcast client since not hazelcast instance setted before starting scheduler.");
      hazelcastClient = Hazelcast.newHazelcastInstance();
    }

    // initializing hazelcast maps
    LOG.debug("Initializing hazelcast maps...");
    jobsByKey = hazelcastClient.getMap(HC_JOB_STORE_MAP_JOB);
    triggersByKey = hazelcastClient.getMap(HC_JOB_STORE_TRIGGER_BY_KEY_MAP);
    jobsByGroup = hazelcastClient.getMultiMap(HC_JOB_STORE_MAP_JOB_BY_GROUP_MAP);
    triggersByGroup = hazelcastClient.getMultiMap(HC_JOB_STORE_TRIGGER_KEY_BY_GROUP_MAP);
    pausedTriggerGroups = hazelcastClient.getSet(HC_JOB_STORE_PAUSED_TRIGGER_GROUPS);
    pausedJobGroups = hazelcastClient.getSet(HC_JOB_STORE_PAUSED_JOB_GROUPS);
    calendarsByName = hazelcastClient.getMap(HC_JOB_CALENDAR_MAP);
    triggers = hazelcastClient.getQueue(HC_JOB_STORE_TRIGGERS_QUEUE);

    triggersByKey.addIndex("nextFireTime", true);

    LOG.debug("Hazelcast Job Store Initialized.");
  }

  @Override
  public void schedulerStarted()
    throws SchedulerException {

    LOG.info("Hazelcast Job Store started successfully");
    schedulerRunning = true;
  }

  @Override
  public void schedulerPaused() {

    schedulerRunning = false;

  }

  @Override
  public void schedulerResumed() {

    schedulerRunning = true;
  }

  @Override
  public void shutdown() {

    hazelcastClient.shutdown();
  }

  @Override
  public boolean supportsPersistence() {

    return true;
  }

  @Override
  public long getEstimatedTimeToReleaseAndAcquireTrigger() {

    return 5;
  }

  @Override
  public boolean isClustered() {

    return true;
  }

  @Override
  public void storeJobAndTrigger(final JobDetail newJob,
      final OperableTrigger newTrigger)
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    storeJob(newJob, false);
    storeTrigger(newTrigger, false);
  }

  @Override
  public void storeJob(final JobDetail job, boolean replaceExisting)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    final JobDetail newJob = (JobDetail) job.clone();
    final JobKey newJobKey = newJob.getKey();

    if (jobsByKey.containsKey(newJobKey) && !replaceExisting) {
      throw new ObjectAlreadyExistsException(newJob);
    }

    jobsByKey.lock(newJobKey, 2, TimeUnit.SECONDS);
    try {
      jobsByKey.put(newJobKey, newJob);
      jobsByGroup.put(newJobKey.getGroup(), newJobKey);
    } finally {
      try {
        jobsByKey.unlock(newJobKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }
  }

  @Override
  public void storeJobsAndTriggers(
      final Map<JobDetail, Set<? extends Trigger>> triggersAndJobs,
      boolean replace)
    throws ObjectAlreadyExistsException,
    JobPersistenceException {

    if (!replace) {

      // validate if anything already exists
      for (final Entry<JobDetail, Set<? extends Trigger>> e : triggersAndJobs
          .entrySet()) {
        final JobDetail jobDetail = e.getKey();
        if (checkExists(jobDetail.getKey())) {
          throw new ObjectAlreadyExistsException(jobDetail);
        }
        for (final Trigger trigger : e.getValue()) {
          if (checkExists(trigger.getKey())) {
            throw new ObjectAlreadyExistsException(trigger);
          }
        }
      }
    }
    // do bulk add...
    for (final Entry<JobDetail, Set<? extends Trigger>> e : triggersAndJobs
        .entrySet()) {
      storeJob(e.getKey(), true);
      for (final Trigger trigger : e.getValue()) {
        storeTrigger((OperableTrigger) trigger, true);
      }
    }

  }

  @Override
  public boolean removeJob(final JobKey jobKey)
    throws JobPersistenceException {

    boolean removed = false;
    if (jobsByKey.containsKey(jobKey)) {
      final List<OperableTrigger> triggersForJob = getTriggersForJob(jobKey);

      for (final OperableTrigger trigger : triggersForJob) {
        removeTrigger(trigger.getKey(), false);
      }

      jobsByKey.lock(jobKey, 2, TimeUnit.SECONDS);
      try {
        jobsByGroup.remove(jobKey.getGroup(), jobKey);
        removed = jobsByKey.remove(jobKey) != null;
      } finally {
        try {
          jobsByKey.unlock(jobKey);
        } catch (IllegalMonitorStateException ex) {
          LOG.warn("Error unlocking since it is already released.", ex);
        }
      }
    }
    return removed;

  }

  @Override
  public boolean removeJobs(final List<JobKey> jobKeys)
    throws JobPersistenceException {

    boolean allRemoved = true;

    for (final JobKey key : jobKeys) {
      allRemoved = removeJob(key) && allRemoved;
    }

    return allRemoved;
  }

  @Override
  public JobDetail retrieveJob(final JobKey jobKey)
    throws JobPersistenceException {

    return jobKey != null && jobsByKey.containsKey(jobKey)
        ? (JobDetail) jobsByKey
            .get(jobKey).clone()
        : null;
  }

  @Override
  public void storeTrigger(OperableTrigger trigger, boolean replaceExisting)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    final OperableTrigger newTrigger = (OperableTrigger) trigger.clone();
    final TriggerKey triggerKey = newTrigger.getKey();

    triggersByKey.lock(triggerKey, 2, TimeUnit.SECONDS);
    try {
      boolean containsKey = triggersByKey.containsKey(triggerKey);
      if (containsKey && !replaceExisting) {
        throw new ObjectAlreadyExistsException(newTrigger);
      }

      if (retrieveJob(newTrigger.getJobKey()) == null) {
        throw new JobPersistenceException("The job (" + newTrigger.getJobKey()
            + ") referenced by the trigger does not exist.");
      }

      boolean shouldBePaused = pausedJobGroups.contains(newTrigger.getJobKey()
          .getGroup()) || pausedTriggerGroups.contains(triggerKey.getGroup());
      final TriggerState state = shouldBePaused
          ? PAUSED
          : NORMAL;

      final TriggerWrapper newTriger = newTriggerWrapper(newTrigger, state);
      triggersByKey.put(newTriger.key, newTriger);
      triggers.add(newTriger.key);
      triggersByGroup.put(triggerKey.getGroup(), triggerKey);
    } finally {
      try {
        triggersByKey.unlock(triggerKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }
  }

  @Override
  public boolean removeTrigger(TriggerKey triggerKey)
    throws JobPersistenceException {

    return removeTrigger(triggerKey, true);
  }

  @Override
  public boolean removeTriggers(final List<TriggerKey> triggerKeys)
    throws JobPersistenceException {

    boolean allRemoved = true;
    for (TriggerKey key : triggerKeys) {
      allRemoved = removeTrigger(key) && allRemoved;
    }

    return allRemoved;
  }

  @Override
  public boolean replaceTrigger(final TriggerKey triggerKey,
      final OperableTrigger newTrigger)
    throws JobPersistenceException {

    newTrigger.setKey(triggerKey);
    storeTrigger(newTrigger, true);
    return true;
  }

  @Override
  public OperableTrigger retrieveTrigger(final TriggerKey triggerKey)
    throws JobPersistenceException {

    return triggerKey != null && triggersByKey.containsKey(triggerKey)
        ? (OperableTrigger) triggersByKey
            .get(triggerKey).getTrigger().clone()
        : null;
  }

  @Override
  public boolean checkExists(JobKey jobKey)
    throws JobPersistenceException {

    return jobsByKey.containsKey(jobKey);
  }

  @Override
  public boolean checkExists(TriggerKey triggerKey)
    throws JobPersistenceException {

    triggers.contains(triggerKey);
    return triggersByKey.containsKey(triggerKey);
  }

  @Override
  public void clearAllSchedulingData()
    throws JobPersistenceException {

    for (final TriggerKey triggerKey : triggersByKey.keySet()) {
      removeTrigger(triggerKey);
    }

    pausedTriggerGroups.stream().
        forEach((group) -> {
          pausedTriggerGroups.remove(group);
        });

    for (final JobKey jobKey : jobsByKey.keySet()) {
      removeJob(jobKey);
    }

    pausedJobGroups.stream().
        forEach((pausedJobGroup) -> {
          pausedJobGroups.remove(pausedJobGroup);
        });

    for (final String calName : calendarsByName.keySet()) {
      removeCalendar(calName);
    }
    
    triggers.clear();
  }

  @Override
  public void storeCalendar(final String calName, final Calendar cal,
      boolean replaceExisting, boolean updateTriggers)
    throws ObjectAlreadyExistsException, JobPersistenceException {

    final Calendar calendar = (Calendar) cal.clone();
    if (calendarsByName.containsKey(calName) && !replaceExisting) {
      throw new ObjectAlreadyExistsException("Calendar with name '" + calName
          + "' already exists.");
    } else {
      calendarsByName.put(calName, calendar);
    }
  }

  @Override
  public boolean removeCalendar(String calName)
    throws JobPersistenceException {

    int numRefs = 0;
    for (TriggerWrapper trigger : triggersByKey.values()) {
      OperableTrigger trigg = trigger.trigger;
      if (trigg.getCalendarName() != null
          && trigg.getCalendarName().equals(calName)) {
        numRefs++;
      }
    }

    if (numRefs > 0) {
      throw new JobPersistenceException(
          "Calender cannot be removed if it referenced by a Trigger!");
    }
    return (calendarsByName.remove(calName) != null);
  }

  @Override
  public Calendar retrieveCalendar(String calName)
    throws JobPersistenceException {

    return calendarsByName.get(calName);
  }

  @Override
  public int getNumberOfJobs()
    throws JobPersistenceException {

    return jobsByKey.size();
  }

  @Override
  public int getNumberOfTriggers()
    throws JobPersistenceException {

    return triggersByKey.size();
  }

  @Override
  public int getNumberOfCalendars()
    throws JobPersistenceException {

    return calendarsByName.size();
  }

  @Override
  public Set<JobKey> getJobKeys(final GroupMatcher<JobKey> matcher)
    throws JobPersistenceException {

    Set<JobKey> outList = null;
    final StringMatcher.StringOperatorName operator = matcher
        .getCompareWithOperator();
    final String groupNameCompareValue = matcher.getCompareToValue();

    switch (operator) {
    case EQUALS:
      final Collection<JobKey> jobKeys = jobsByGroup.
          get(groupNameCompareValue);
      if (jobKeys != null) {
        outList = new HashSet<>();
        for (JobKey jobKey : jobKeys) {
          if (jobKey != null) {
            outList.add(jobKey);
          }
        }
      }
      break;
    default:
      for (String groupName : jobsByGroup.keySet()) {
        if (operator.evaluate(groupName, groupNameCompareValue)) {
          if (outList == null) {
            outList = new HashSet<>();
          }
          for (JobKey jobKey : jobsByGroup.get(groupName)) {
            if (jobKey != null) {
              outList.add(jobKey);
            }
          }
        }
      }
    }
    return outList == null
        ? java.util.Collections.<JobKey> emptySet()
        : outList;
  }

  @Override
  public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher)
    throws JobPersistenceException {

    Set<TriggerKey> outList = null;
    StringMatcher.StringOperatorName operator = matcher
        .getCompareWithOperator();
    String groupNameCompareValue = matcher.getCompareToValue();
    switch (operator) {
    case EQUALS:
      Collection<TriggerKey> triggerKeys = triggersByGroup
          .get(groupNameCompareValue);
      if (triggerKeys != null) {
        outList = newHashSet();
        for (TriggerKey triggerKey : triggerKeys) {
          if (triggerKey != null) {
            outList.add(triggerKey);
          }
        }
      }
      break;
    default:
      for (String groupName : triggersByGroup.keySet()) {
        if (operator.evaluate(groupName, groupNameCompareValue)) {
          if (outList == null) {
            outList = newHashSet();
          }
          for (TriggerKey triggerKey : triggersByGroup.get(groupName)) {
            if (triggerKey != null) {
              outList.add(triggerKey);
            }
          }
        }
      }
    }
    return outList == null
        ? java.util.Collections.<TriggerKey> emptySet()
        : outList;
  }

  @Override
  public List<String> getJobGroupNames()
    throws JobPersistenceException {

    return newArrayList(jobsByGroup.keySet());
  }

  @Override
  public List<String> getTriggerGroupNames()
    throws JobPersistenceException {

    return new LinkedList<>(triggersByGroup.keySet());
  }

  @Override
  public List<String> getCalendarNames()
    throws JobPersistenceException {

    return new LinkedList<>(calendarsByName.keySet());
  }

  @Override
  public List<OperableTrigger> getTriggersForJob(final JobKey jobKey)
    throws JobPersistenceException {

    if (jobKey == null) {
      return Collections.emptyList();
    }

    return triggersByKey.values(new TriggerByJobPredicate(jobKey))
        .stream()
        .map(v -> (OperableTrigger) v.getTrigger())
        .collect(Collectors.toList());
  }

  @Override
  public void pauseTrigger(TriggerKey triggerKey)
    throws JobPersistenceException {

    triggersByKey.lock(triggerKey, 2, TimeUnit.SECONDS);
    try {
      final TriggerWrapper newTrigger = newTriggerWrapper(
          triggersByKey.get(triggerKey), PAUSED);
      triggersByKey.put(newTrigger.key, newTrigger);
    } finally {
      try {
        triggersByKey.unlock(triggerKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }
  }

  @Override
  public org.quartz.Trigger.TriggerState getTriggerState(TriggerKey triggerKey)
    throws JobPersistenceException {

    triggersByKey.lock(triggerKey, 2, TimeUnit.SECONDS);
    org.quartz.Trigger.TriggerState result = org.quartz.Trigger.TriggerState.NONE;
    try {
      TriggerWrapper tw = triggersByKey.get(triggerKey);
      if (tw != null) {
        result = toClassicTriggerState(tw.getState());
      }
    } finally {
      try {
        triggersByKey.unlock(triggerKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }
    return result;
  }

  @Override
  public void resumeTrigger(TriggerKey triggerKey)
    throws JobPersistenceException {

    triggersByKey.lock(triggerKey, 2, TimeUnit.SECONDS);
    try {
      if (schedulerRunning) {
        final TriggerWrapper newTrigger = newTriggerWrapper(triggersByKey.get(triggerKey), NORMAL);
        triggersByKey.put(newTrigger.key, newTrigger);
      }
    } finally {
      try {
        triggersByKey.unlock(triggerKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }
  }

  @Override
  public Collection<String> pauseTriggers(GroupMatcher<TriggerKey> matcher)
    throws JobPersistenceException {

    List<String> pausedGroups = new LinkedList<>();
    StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
    switch (operator) {
    case EQUALS:
      if (pausedTriggerGroups.add(matcher.getCompareToValue())) {
        pausedGroups.add(matcher.getCompareToValue());
      }
      break;
    default:
      for (String group : triggersByGroup.keySet()) {
        if (operator.evaluate(group, matcher.getCompareToValue())) {
          if (pausedTriggerGroups.add(matcher.getCompareToValue())) {
            pausedGroups.add(group);
          }
        }
      }
    }

    for (String pausedGroup : pausedGroups) {
      Set<TriggerKey> keys = getTriggerKeys(GroupMatcher.triggerGroupEquals(pausedGroup));
      for (TriggerKey key : keys) {
        pauseTrigger(key);
      }
    }
    return pausedGroups;
  }

  @Override
  public Collection<String> resumeTriggers(GroupMatcher<TriggerKey> matcher)
    throws JobPersistenceException {

    Set<String> resumeGroups = new HashSet<>();
    Set<TriggerKey> keys = getTriggerKeys(matcher);
    for (TriggerKey triggerKey : keys) {
      resumeGroups.add(triggerKey.getGroup());
      TriggerWrapper tw = triggersByKey.get(triggerKey);
      OperableTrigger trigger = tw.getTrigger();
      String jobGroup = trigger.getJobKey().getGroup();
      if (pausedJobGroups.contains(jobGroup)) {
        continue;
      }
      resumeTrigger(triggerKey);
    }
    for (String group : resumeGroups) {
      pausedTriggerGroups.remove(group);
    }
    return new ArrayList<>(resumeGroups);
  }

  @Override
  public void pauseJob(JobKey jobKey)
    throws JobPersistenceException {

    boolean found = jobsByKey.containsKey(jobKey);
    if (!found) {
      return;
    }
    jobsByKey.lock(jobKey, 2, TimeUnit.SECONDS);
    try {
      List<OperableTrigger> triggersForJob = getTriggersForJob(jobKey);
      for (OperableTrigger trigger : triggersForJob) {
        pauseTrigger(trigger.getKey());
      }
    } finally {
      try {
        jobsByKey.unlock(jobKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }
  }

  @Override
  public void resumeJob(JobKey jobKey)
    throws JobPersistenceException {

    boolean found = jobsByKey.containsKey(jobKey);
    if (!found) {
      return;
    }
    jobsByKey.lock(jobKey, 2, TimeUnit.SECONDS);
    try {
      List<OperableTrigger> triggersForJob = getTriggersForJob(jobKey);
      for (OperableTrigger trigger : triggersForJob) {
        resumeTrigger(trigger.getKey());
      }
    } finally {
      try {
        jobsByKey.unlock(jobKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }

  }

  @Override
  public Collection<String> pauseJobs(GroupMatcher<JobKey> groupMatcher)
    throws JobPersistenceException {

    List<String> pausedGroups = new LinkedList<>();
    StringMatcher.StringOperatorName operator = groupMatcher
        .getCompareWithOperator();
    switch (operator) {
    case EQUALS:
      if (pausedJobGroups.add(groupMatcher.getCompareToValue())) {
        pausedGroups.add(groupMatcher.getCompareToValue());
      }
      break;
    default:
      for (String jobGroup : jobsByGroup.keySet()) {
        if (operator.evaluate(jobGroup, groupMatcher.getCompareToValue())) {
          if (pausedJobGroups.add(jobGroup)) {
            pausedGroups.add(jobGroup);
          }
        }
      }
    }

    for (String groupName : pausedGroups) {
      for (JobKey jobKey : getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {
        pauseJob(jobKey);
      }
    }
    return pausedGroups;
  }

  @Override
  public Collection<String> resumeJobs(GroupMatcher<JobKey> matcher)
    throws JobPersistenceException {

    Set<String> resumeGroups = new HashSet<>();
    Set<JobKey> jobKeys = getJobKeys(matcher);
    for (JobKey jobKey : jobKeys) {
      resumeGroups.add(jobKey.getGroup());
      resumeJob(jobKey);
    }
    resumeGroups.stream().
        forEach((group) -> {
          pausedJobGroups.remove(group);
        });
    return new ArrayList<>(resumeGroups);
  }

  @Override
  public Set<String> getPausedTriggerGroups()
    throws JobPersistenceException {

    return new HashSet<>(pausedTriggerGroups);
  }

  @Override
  public void pauseAll()
    throws JobPersistenceException {

    for (String triggerGroup : triggersByGroup.keySet()) {
      pauseTriggers(GroupMatcher.triggerGroupEquals(triggerGroup));
    }
  }

  @Override
  public void resumeAll()
    throws JobPersistenceException {

    List<String> triggerGroupNames = getTriggerGroupNames();
    for (String triggerGroup : triggerGroupNames) {
      resumeTriggers(GroupMatcher.triggerGroupEquals(triggerGroup));
    }
  }

  /**
   * @return @throws org.quartz.JobPersistenceException
   * @see org.quartz.spi.JobStore#acquireNextTriggers(long, int, long)
   *
   * @param noLaterThan
   *          highest value of <code>getNextFireTime()</code> of the
   *          triggers (exclusive)
   * @param timeWindow
   *          highest value of <code>getNextFireTime()</code> of the
   *          triggers (inclusive)
   * @param maxCount
   *          maximum number of trigger keys allow to acquired in the
   *          returning list.
   */
  @Override
  public List<OperableTrigger> acquireNextTriggers(long noLaterThan,
      int maxCount, long timeWindow)
    throws JobPersistenceException {

    if (triggers.isEmpty()) {
      return Collections.EMPTY_LIST;
    }

    //    triggers.isEmpty();

    long limit = noLaterThan + timeWindow;
    final int size = triggers.size();

    //    if (triggers.isEmpty()) {
    //      return Collections.EMPTY_LIST;
    //    }

    List<OperableTrigger> result = new ArrayList<>();
    Set<JobKey> acquiredJobKeysForNoConcurrentExec = new HashSet<>();
    Set<TriggerKey> excludedTriggers = new HashSet<>();

    for (int i = 0; i < size; i++) {
      TriggerWrapper tw;
      try {
        //        tw = iterator.next();
        tw = triggersByKey.get(triggers.poll());
        if (tw == null) {
          break;
        }
        triggersByKey.remove(tw.key);
      } catch (java.util.NoSuchElementException nsee) {
        break;
      }

      if (tw.trigger.getNextFireTime() == null) {
        triggers.add(tw.key);
        continue;
      }

      if (applyMisfire(tw)) {
        if (tw.trigger.getNextFireTime() != null) {
          storeTriggerWrapper(newTriggerWrapper(tw, NORMAL));
        }
        continue;
      }

      if (tw.getTrigger().getNextFireTime().getTime() > limit) {
        storeTriggerWrapper(newTriggerWrapper(tw, NORMAL));
        continue;
      }

      // If trigger's job is set as @DisallowConcurrentExecution, and it has
      // already been added to result, then
      // put it back into the timeTriggers set and continue to search for next
      // trigger.
      final JobKey jobKey = tw.trigger.getJobKey();
      final JobDetail job = jobsByKey.get(tw.trigger.getJobKey());
      if (job.isConcurrentExectionDisallowed()) {
        if (acquiredJobKeysForNoConcurrentExec.contains(jobKey)) {
          excludedTriggers.add(tw.key);
          continue; // go to next trigger in store.
        } else {
          acquiredJobKeysForNoConcurrentExec.add(jobKey);
        }
      }

      OperableTrigger trig = (OperableTrigger) tw.trigger.clone();
      trig.setFireInstanceId(getFiredTriggerRecordId());
      storeTriggerWrapper(newTriggerWrapper(trig, ACQUIRED));

      result.add(trig);

      if (result.size() == maxCount) {
        break;
      }
    }

    // If we did excluded triggers to prevent ACQUIRE state due to
    // DisallowConcurrentExecution, we need to add
    // them back to store.
    if (excludedTriggers.size() > 0) {
      triggers.addAll(excludedTriggers);
    }
    return result;
  }

  private boolean applyMisfire(TriggerWrapper tw)
    throws JobPersistenceException {

    long misfireTime = System.currentTimeMillis();
    if (misfireThreshold > 0) {
      misfireTime -= misfireThreshold;
    }

    Date tnft = tw.trigger.getNextFireTime();
    if (tnft == null
        || tnft.getTime() > misfireTime
        || tw.trigger.getMisfireInstruction() == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) {
      return false;
    }

    Calendar cal = null;
    if (tw.trigger.getCalendarName() != null) {
      cal = retrieveCalendar(tw.trigger.getCalendarName());
    }

    this.schedSignaler
        .notifyTriggerListenersMisfired((OperableTrigger) tw.trigger.clone());

    tw.trigger.updateAfterMisfire(cal);

    if (tw.trigger.getNextFireTime() == null) {
      storeTriggerWrapper(newTriggerWrapper(tw, STATE_COMPLETED));
      schedSignaler.notifySchedulerListenersFinalized(tw.trigger);
    } else if (tnft.equals(tw.trigger.getNextFireTime())) {
      return false;
    }

    return true;
  }

  protected long getMisfireTime() {

    long misfireTime = System.currentTimeMillis();
    if (misfireThreshold > 0) {
      misfireTime -= misfireThreshold;
    }

    return (misfireTime > 0)
        ? misfireTime
        : 0;
  }

  private static long ftrCtr = System.currentTimeMillis();

  protected synchronized String getFiredTriggerRecordId() {

    return instanceId + ftrCtr++;
  }

  @Override
  public void releaseAcquiredTrigger(OperableTrigger trigger) {

    TriggerKey triggerKey = trigger.getKey();
    triggersByKey.lock(triggerKey, 2, TimeUnit.SECONDS);
    try {
      storeTriggerWrapper(newTriggerWrapper(trigger, WAITING));
    } finally {
      try {
        triggersByKey.unlock(triggerKey);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }

  }

  @Override
  public List<TriggerFiredResult> triggersFired(
      List<OperableTrigger> firedTriggers)
    throws JobPersistenceException {

    List<TriggerFiredResult> results = new ArrayList<>();

    for (OperableTrigger trigger : firedTriggers) {
      TriggerWrapper tw = triggersByKey.get(trigger.getKey());
      // was the trigger deleted since being acquired?
      if (tw == null || tw.trigger == null) {
        continue;
      }
      // was the trigger completed, paused, blocked, etc. since being acquired?
      if (tw.getState() != ACQUIRED) {
        continue;
      }

      Calendar cal = null;
      if (tw.trigger.getCalendarName() != null) {
        cal = retrieveCalendar(tw.trigger.getCalendarName());
        if (cal == null) {
          continue;
        }
      }
      Date prevFireTime = trigger.getPreviousFireTime();
      // call triggered on our copy, and the scheduler's copy
      tw.trigger.triggered(cal);
      trigger.triggered(cal);

      storeTriggerWrapper(newTriggerWrapper(trigger, WAITING));

      TriggerFiredBundle bndle = new TriggerFiredBundle(retrieveJob(tw.jobKey),
          trigger, cal, false, new Date(), trigger.getPreviousFireTime(),
          prevFireTime, trigger.getNextFireTime());

      JobDetail job = bndle.getJobDetail();

      if (job.isConcurrentExectionDisallowed()) {
        ArrayList<TriggerWrapper> trigs = getTriggerWrappersForJob(job.getKey());
        for (TriggerWrapper ttw : trigs) {
          if (ttw.getState() == WAITING) {
            ttw = newTriggerWrapper(ttw, BLOCKED);
          }
          if (ttw.getState() == PAUSED) {
            ttw = newTriggerWrapper(ttw, BLOCKED);
          }
        }
      }

      results.add(new TriggerFiredResult(bndle));
    }
    return results;
  }

  private ArrayList<TriggerWrapper> getTriggerWrappersForJob(JobKey jobKey) {

    ArrayList<TriggerWrapper> trigList = new ArrayList<>();

    triggersByKey.values().stream().
        filter((trigger) -> (trigger.jobKey.equals(jobKey))).
        forEach((trigger) -> {
          trigList.add(trigger);
        });

    return trigList;
  }

  @Override
  public void triggeredJobComplete(
      OperableTrigger trigger,
      JobDetail jobDetail,
      Trigger.CompletedExecutionInstruction triggerInstCode) {

    if (jobDetail.isPersistJobDataAfterExecution()) {
      JobKey jobKey = jobDetail.getKey();
      jobsByKey.lock(jobKey, 2, TimeUnit.SECONDS);
      try {
        jobsByKey.put(jobKey, jobDetail);
        jobsByGroup.put(jobKey.getGroup(), jobKey);
      } finally {
        try {
          jobsByKey.unlock(jobKey);
        } catch (IllegalMonitorStateException ex) {
          LOG.warn("Error unlocking since it is already released.", ex);
        }
      }
    }
  }

  @Override
  public void setInstanceName(final String instanceName) {

    this.instanceName = instanceName;
  }

  @Override
  public void setInstanceId(String instanceId) {

    this.instanceId = instanceId;
  }

  @Override
  public void setThreadPoolSize(int poolSize) {

    // not need
  }

  public void setMisfireThreshold(long misfireThreshold) {

    if (misfireThreshold < 1) {
      throw new IllegalArgumentException("Misfire threshold must be larger than 0");
    }
    this.misfireThreshold = misfireThreshold;
  }

  private boolean removeTrigger(TriggerKey key, boolean removeOrphanedJob)
    throws JobPersistenceException {

    boolean removed = false;

    // remove from triggers by FQN map
    triggersByKey.lock(key, 2, TimeUnit.SECONDS);
    try {
      final TriggerWrapper tw = triggersByKey.remove(key);
      removed = tw != null;
      if (removed) {
        // remove from triggers by group
        triggersByGroup.remove(key.getGroup(), key);

        if (removeOrphanedJob) {
          JobDetail job = jobsByKey.get(tw.jobKey);
          List<OperableTrigger> trigs = getTriggersForJob(tw.jobKey);
          if ((trigs == null || trigs.isEmpty()) && !job.isDurable()) {
            if (removeJob(job.getKey())) {
              schedSignaler.notifySchedulerListenersJobDeleted(job.getKey());
            }
          }
        }
      }
    } finally {
      try {
        triggersByKey.unlock(key);
      } catch (IllegalMonitorStateException ex) {
        LOG.warn("Error unlocking since it is already released.", ex);
      }
    }

    return removed;
  }

  private void storeTriggerWrapper(final TriggerWrapper tw) {

    if (tw.getState() == NORMAL || tw.getState() == WAITING) {
      triggers.add(tw.key);
    }
    triggersByKey.put(tw.key, tw);
  }

}

/**
 * Filter triggers with a given job key
 *
 * @author fferreira
 */
class TriggerByJobPredicate implements Predicate<JobKey, TriggerWrapper> {

  private JobKey key;

  public TriggerByJobPredicate(JobKey key) {

    this.key = key;
  }

  @Override
  public boolean apply(Entry<JobKey, TriggerWrapper> entry) {

    return key != null && entry != null && key.equals(entry.getValue().jobKey);
  }
}

/**
 * Filter triggers with a given fire start time, end time and state.
 *
 * @author fferreira
 */
class TriggersPredicate implements Predicate<TriggerKey, TriggerWrapper> {

  long noLaterThanWithTimeWindow;
  TriggerState noState;

  public TriggersPredicate(long noLaterThanWithTimeWindow,
      TriggerState state) {

    this.noLaterThanWithTimeWindow = noLaterThanWithTimeWindow;
    this.noState = state;
  }

  public TriggersPredicate(long noLaterThanWithTimeWindow) {

    this(noLaterThanWithTimeWindow, ACQUIRED);
  }

  @Override
  public boolean apply(Entry<TriggerKey, TriggerWrapper> entry) {

    if (entry.getValue() == null || entry.getValue().getNextFireTime() == null) {
      return false;
    }

    return entry.getValue().getNextFireTime() <= noLaterThanWithTimeWindow
        && entry.getValue().getState() != noState;
  }
}
