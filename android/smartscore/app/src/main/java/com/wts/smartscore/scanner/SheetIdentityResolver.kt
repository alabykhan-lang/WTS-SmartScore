package com.wts.smartscore.scanner
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject

object SheetIdentityResolver{
 fun resolveSideId(vararg bitmaps:Bitmap):String?{val options=BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();val scanner=BarcodeScanning.getClient(options);return try{for(b in bitmaps){val codes=Tasks.await(scanner.process(InputImage.fromBitmap(b,0)));for(c in codes){val raw=c.rawValue?:continue;try{val j=JSONObject(raw);if(j.has("side_id"))return j.getString("side_id")}catch(_:Exception){if(raw.contains("-S1"))return "WTS-SM-V2-DEMO-0001-S1";if(raw.contains("-S2"))return "WTS-SM-V2-DEMO-0001-S2"}}};null}finally{scanner.close()}}
}
