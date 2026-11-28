package com.example.ui.components

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DEFAULT_TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
private val mobileAdsInitialized = AtomicBoolean(false)
private val _mobileAdsReady = MutableStateFlow(false)
val mobileAdsReady: StateFlow<Boolean> = _mobileAdsReady.asStateFlow()

fun initializeMobileAdsOnce(context: Context, enabled: Boolean = true) {
    if (!enabled) return
    if (mobileAdsInitialized.compareAndSet(false, true)) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                MobileAds.initialize(appContext) {
                    if (BuildConfig.DEBUG) {
                        runCatching {
                            val config = RequestConfiguration.Builder()
                                .setTestDeviceIds(listOf(AdRequest.DEVICE_ID_EMULATOR))
                                .build()
                            MobileAds.setRequestConfiguration(config)
                        }
                    }
                    _mobileAdsReady.value = true
                }
            } catch (err: Throwable) {
                android.util.Log.w("AdMobBanner", "Safe MobileAds init skipped", err)
                _mobileAdsReady.value = false
            }
        }
    }
}

internal fun resolveBannerAdUnitId(
    configuredId: String = BuildConfig.ADMOB_BANNER_AD_UNIT_ID
): String {
    val trimmed = configuredId.trim()
    return if (
        trimmed.startsWith("ca-app-pub-") &&
        trimmed.contains('/') &&
        trimmed.length >= 20
    ) {
        trimmed
    } else {
        DEFAULT_TEST_BANNER_UNIT_ID
    }
}

@Composable
fun AdMobBannerBar(
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (!enabled) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val adUnitId = remember { resolveBannerAdUnitId() }
    val adsReady by mobileAdsReady.collectAsStateWithLifecycle()

    LaunchedEffect(context, enabled) {
        if (enabled && !adsReady) {
            kotlinx.coroutines.delay(1_200L)
            initializeMobileAdsOnce(context, enabled = true)
        }
    }

    if (!adsReady) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("admob_banner_container"),
        contentAlignment = Alignment.Center
    ) {
        val adWidthDp = maxWidth.value.toInt().coerceIn(320, 728)
        var adFailed by remember(adUnitId, adWidthDp) { mutableStateOf(false) }

        val adView = remember(context, adUnitId, adWidthDp) {
            runCatching {
                AdView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    this.adUnitId = adUnitId
                    setAdSize(
                        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                            context,
                            adWidthDp
                        )
                    )
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            adFailed = false
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            adFailed = true
                        }
                    }
                    loadAd(AdRequest.Builder().build())
                }
            }.getOrNull()
        }

        if (adView == null) return@BoxWithConstraints

        DisposableEffect(lifecycleOwner, adView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> runCatching { adView.resume() }
                    Lifecycle.Event.ON_PAUSE -> runCatching { adView.pause() }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                runCatching { adView.destroy() }
            }
        }

        if (!adFailed) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(
                        modifier = Modifier.align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    AndroidView(
                        factory = { adView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
