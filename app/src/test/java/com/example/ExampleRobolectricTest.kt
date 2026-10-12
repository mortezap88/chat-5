package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("StreamChat", appName)
  }

  @Test
  fun `test TavilyContextDecider triggers for internet access and models`() {
    org.junit.Assert.assertTrue(com.example.network.TavilyContextDecider.shouldSearch("do you have access to internet?"))
    org.junit.Assert.assertTrue(com.example.network.TavilyContextDecider.shouldSearch("what is the latest model?"))
    org.junit.Assert.assertTrue(com.example.network.TavilyContextDecider.shouldSearch("can you browse the web?"))
  }
}
