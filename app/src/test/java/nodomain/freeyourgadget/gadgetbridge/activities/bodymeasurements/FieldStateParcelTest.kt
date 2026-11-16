package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import android.os.Bundle
import android.os.Parcel
import androidx.core.os.BundleCompat
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldStateParcelTest : TestBase() {
    @Test
    fun `field state survives a parcel round trip`() {
        val fields = arrayListOf(
            FieldState(104, 81.915f, "32.25", "33,5"),
            FieldState(105, null, "", ""),
        )
        val bundle = Bundle().apply { putParcelableArrayList("fields", fields) }
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = Bundle.CREATOR.createFromParcel(parcel)
            restored.classLoader = FieldState::class.java.classLoader
            assertEquals(fields, BundleCompat.getParcelableArrayList(restored, "fields", FieldState::class.java))
        } finally {
            parcel.recycle()
        }
    }
}
