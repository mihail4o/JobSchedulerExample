package com.balivo.jobschedulerexample

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.os.PersistableBundle
import android.support.annotation.ColorRes
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

import com.balivo.jobschedulerexample.service.MyJobService

import java.lang.ref.WeakReference


/**
 * Schedules and configures jobs to be executed by a [JobScheduler].
 *
 *
 * [MyJobService] can send messages to this via a [Messenger]
 * that is sent in the Intent that starts the Service.
 */
class MainActivity : Activity() {

    private var mDelayEditText: EditText? = null
    private var mDeadlineEditText: EditText? = null
    private var mDurationTimeEditText: EditText? = null
    private var mWiFiConnectivityRadioButton: RadioButton? = null
    private var mAnyConnectivityRadioButton: RadioButton? = null
    private var mRequiresChargingCheckBox: CheckBox? = null
    private var mRequiresIdleCheckbox: CheckBox? = null

    private var mServiceComponent: ComponentName? = null

    private var mJobId = 0

    // Handler for incoming messages from the service.
    private var mHandler: IncomingMessageHandler? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up UI.
        mDelayEditText = findViewById<View>(R.id.delay_time) as EditText
        mDurationTimeEditText = findViewById<View>(R.id.duration_time) as EditText
        mDeadlineEditText = findViewById<View>(R.id.deadline_time) as EditText
        mWiFiConnectivityRadioButton = findViewById<View>(R.id.checkbox_unmetered) as RadioButton
        mAnyConnectivityRadioButton = findViewById<View>(R.id.checkbox_any) as RadioButton
        mRequiresChargingCheckBox = findViewById<View>(R.id.checkbox_charging) as CheckBox
        mRequiresIdleCheckbox = findViewById<View>(R.id.checkbox_idle) as CheckBox
        mServiceComponent = ComponentName(this, MyJobService::class.java)

        mHandler = IncomingMessageHandler(this)
    }

    override fun onStop() {
        // A service can be "started" and/or "bound". In this case, it's "started" by this Activity
        // and "bound" to the JobScheduler (also called "Scheduled" by the JobScheduler). This call
        // to stopService() won't prevent scheduled jobs to be processed. However, failing
        // to call stopService() would keep it alive indefinitely.
        stopService(Intent(this, MyJobService::class.java))
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // Start service and provide it a way to communicate with this class.
        val startServiceIntent = Intent(this, MyJobService::class.java)
        val messengerIncoming = Messenger(mHandler)
        startServiceIntent.putExtra(MESSENGER_INTENT_KEY, messengerIncoming)
        startService(startServiceIntent)
    }

    /**
     * Executed when user clicks on SCHEDULE JOB.
     */
    fun scheduleJob(v: View) {
        val builder = JobInfo.Builder(mJobId++, mServiceComponent!!)

        val delay = mDelayEditText!!.text.toString()
        if (!TextUtils.isEmpty(delay)) {
            builder.setMinimumLatency(java.lang.Long.valueOf(delay) * 1000)
        }
        val deadline = mDeadlineEditText!!.text.toString()
        if (!TextUtils.isEmpty(deadline)) {
            builder.setOverrideDeadline(java.lang.Long.valueOf(deadline) * 1000)
        }
        val requiresUnmetered = mWiFiConnectivityRadioButton!!.isChecked
        val requiresAnyConnectivity = mAnyConnectivityRadioButton!!.isChecked
        if (requiresUnmetered) {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        } else if (requiresAnyConnectivity) {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        }
        builder.setRequiresDeviceIdle(mRequiresIdleCheckbox!!.isChecked)
        builder.setRequiresCharging(mRequiresChargingCheckBox!!.isChecked)

        // Extras, work duration.
        val extras = PersistableBundle()
        var workDuration = mDurationTimeEditText!!.text.toString()
        if (TextUtils.isEmpty(workDuration)) {
            workDuration = "1"
        }
        extras.putLong(WORK_DURATION_KEY, java.lang.Long.valueOf(workDuration) * 1000)

        builder.setExtras(extras)

        // Schedule job
        Log.d(TAG, "Scheduling job")
        val tm = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        tm.schedule(builder.build())
    }

    /**
     * Executed when user clicks on CANCEL ALL.
     */
    fun cancelAllJobs(v: View) {
        val tm = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        tm.cancelAll()
        Toast.makeText(this@MainActivity, R.string.all_jobs_cancelled, Toast.LENGTH_SHORT).show()
    }

    /**
     * Executed when user clicks on FINISH LAST TASK.
     */
    fun finishJob(v: View) {
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val allPendingJobs = jobScheduler.allPendingJobs
        if (allPendingJobs.size > 0) {
            // Finish the last one
            val jobId = allPendingJobs[0].id
            jobScheduler.cancel(jobId)
            Toast.makeText(
                    this@MainActivity, String.format(getString(R.string.cancelled_job), jobId),
                    Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                    this@MainActivity, getString(R.string.no_jobs_to_cancel),
                    Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A [Handler] allows you to send messages associated with a thread. A [Messenger]
     * uses this handler to communicate from [MyJobService]. It's also used to make
     * the start and stop views blink for a short period of time.
     */
    private class IncomingMessageHandler internal constructor(activity: MainActivity) : Handler() {

        // Prevent possible leaks with a weak reference.
        private val mActivity: WeakReference<MainActivity>

        init {
            this.mActivity = WeakReference(activity)
        }/* default looper */

        override fun handleMessage(msg: Message) {
            val mainActivity = mActivity.get()
                    ?: // Activity is no longer available, exit.
                    return
            val showStartView = mainActivity.findViewById<View>(R.id.onstart_textview)
            val showStopView = mainActivity.findViewById<View>(R.id.onstop_textview)
            val m: Message
            when (msg.what) {
            /*
                 * Receives callback from the service when a job has landed
                 * on the app. Turns on indicator and sends a message to turn it off after
                 * a second.
                 */
                MSG_COLOR_START -> {
                    // Start received, turn on the indicator and show text.
                    showStartView.setBackgroundColor(getColor(R.color.start_received))
                    updateParamsTextView(msg.obj, "started")

                    // Send message to turn it off after a second.
                    m = Message.obtain(this, MSG_UNCOLOR_START)
                    sendMessageDelayed(m, 1000L)
                }
            /*
                 * Receives callback from the service when a job that previously landed on the
                 * app must stop executing. Turns on indicator and sends a message to turn it
                 * off after two seconds.
                 */
                MSG_COLOR_STOP -> {
                    // Stop received, turn on the indicator and show text.
                    showStopView.setBackgroundColor(getColor(R.color.stop_received))
                    updateParamsTextView(msg.obj, "stopped")

                    // Send message to turn it off after a second.
                    m = obtainMessage(MSG_UNCOLOR_STOP)
                    sendMessageDelayed(m, 2000L)
                }
                MSG_UNCOLOR_START -> {
                    showStartView.setBackgroundColor(getColor(R.color.none_received))
                    updateParamsTextView(null, "")
                }
                MSG_UNCOLOR_STOP -> {
                    showStopView.setBackgroundColor(getColor(R.color.none_received))
                    updateParamsTextView(null, "")
                }
            }
        }

        private fun updateParamsTextView(jobId: Any?, action: String) {
            val paramsTextView = mActivity.get()!!.findViewById<View>(R.id.task_params) as TextView
            if (jobId == null) {
                paramsTextView.text = ""
                return
            }
            val jobIdText = jobId.toString()
            paramsTextView.setText(String.format("Job ID %s %s", jobIdText, action))
        }

        private fun getColor(@ColorRes color: Int): Int {
            return mActivity.get()!!.getResources().getColor(color)
        }
    }

    companion object {

        private val TAG = MainActivity::class.java!!.getSimpleName()

        val MSG_UNCOLOR_START = 0
        val MSG_UNCOLOR_STOP = 1
        val MSG_COLOR_START = 2
        val MSG_COLOR_STOP = 3

        val MESSENGER_INTENT_KEY = BuildConfig.APPLICATION_ID + ".MESSENGER_INTENT_KEY"
        val WORK_DURATION_KEY = BuildConfig.APPLICATION_ID + ".WORK_DURATION_KEY"
    }
}
