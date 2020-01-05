package com.example.myapplication

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.log
//import kotlin.math.max
import kotlin.math.sqrt

public class MainActivity : AppCompatActivity() {

    val TAG = "SNC"
    val thresholdAmp = 0x00ff
//    val MSG_RECORD_START = 100
//    val MSG_RECORD_END = 110
//    val MSG_FREQ_PEAK = 120
//    val MSG_SILENCE = 130


    private var mInRecording = false
    private var mStop = false

    /* 信号処理 変数 */
    // サンプリングレート (Hz)
    private val samplingRate = 44100

    // 音声データのバッファサイズ (byte) 3584byte
    private val bufferSizeInBytes = AudioRecord.getMinBufferSize(samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT)

    //1792
    private val audioBufferSizeInShort:Int = bufferSizeInBytes/2

    // 録音用バッファ
    private var audioRecordBuf = ShortArray(audioBufferSizeInShort)

    private val zeroPaddingSize = 256
    private val audioFFTSize = audioBufferSizeInShort+zeroPaddingSize
    private var audioFFT = DoubleFFT_1D(audioFFTSize.toLong()) // audioFFTSize int->Long型に合わせる
    private var audioFFTBuffer = DoubleArray(audioFFTSize)

    private var spectrum = DoubleArray(audioFFTSize/2)

    // インスタンスの作成
    private val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC, // 音声のソース
        samplingRate, // サンプリングレート
        AudioFormat.CHANNEL_IN_MONO, // チャネル設定. MONO and STEREO が全デバイスサポート保障
        AudioFormat.ENCODING_PCM_16BIT, // PCM16が全デバイスサポート保障
        bufferSizeInBytes) // バッファ

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_main)

        Log.d(TAG, "audioBufferSizeInShort=$audioBufferSizeInShort")

        button_s.setOnClickListener {
            mInRecording = true
            thread {
                startRecord()
            }
        }
    }

    public override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
    }

    public override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // 別スレッドで表示を更新
        runOnUiThread {
            text_freq.text = getString(R.string.freqview, "-")
        }
    }

    public override fun onDestroy(){
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        mStop = true
        try{
            Thread.sleep(2000)
        }catch (e: InterruptedException){
        }

        audioRecord.stop()
    }

//    val mHandler =  object: Handler() {
//        override fun handleMessage(msg: Message?) {
//            when (msg?.what) {
//                MSG_RECORD_START -> {
//                    Log.d(TAG, "MSG_RECORD_START")
//                    button_s.text = getString(R.string.stop)
//                }
//                MSG_RECORD_END -> {
//                    Log.d(TAG, "MSG_RECORD_END")
//                    button_s.text = getString(R.string.start)
//                }
//                MSG_FREQ_PEAK -> text_freq.text = getString(R.string.freqview, msg.arg1.toString())
//                MSG_SILENCE -> text_freq.text = ""
//            }
//        }
//    }


    fun startRecord(){

        Log.d(TAG, "startRecord")
        var bSilence = false
        var freq = 0.0
//        var scale = 0.0
//        var pitchName:String
        // 集音開始
        audioRecord.startRecording()


        // 音声データを幾つずつ処理するか( = 1フレームのデータの数)
//        audioRecord.positionNotificationPeriod = oneFrameDataCount

        // ここで指定した数になったタイミングで, 後続の onMarkerReached が呼び出される
        // 通常のストリーミング処理では必要なさそう？
//        audioRecord.notificationMarkerPosition = 40000 // 使わないなら設定しない.


        while(mInRecording && !mStop){ // true && false
            audioRecord.read(audioRecordBuf, 0, audioBufferSizeInShort)
            bSilence = true

            for(i in 0 until audioBufferSizeInShort){
                var s = audioRecordBuf[i]
                if (s > thresholdAmp){
                    bSilence = false
                }
            }
            if(bSilence){
//                mHandler.sendEmptyMessage(MSG_SILENCE)
                runOnUiThread {

                    text_freq.text = getString(R.string.freqview, "-")
                }
                continue
            }
            freq = doFFT(audioRecordBuf)
            var scale = convertHertzToScale(freq)
            var pitchName = convertScaleToString(scale)

//            Log.d(TAG, "freq:"+freq)
            runOnUiThread {
                text_freq.text = getString(R.string.freqview, freq.toString())
                text_pname.text = getString(R.string.pnameview, pitchName)
            }
//            val msg = Message()
//            msg.what = MSG_FREQ_PEAK
//            msg.arg1 = freq
//            mHandler.sendMessage(msg)
        }

        audioRecord.stop()
        text_freq.text = getString(R.string.freqview, freq.toString())
//        mHandler.sendEmptyMessage(MSG_RECORD_END)
    }

    private fun stopRecord(){
        Log.d(TAG, "stopRecord")

    }

    private fun doFFT(data: ShortArray): Double{
        for(i in 0 until audioFFTSize-zeroPaddingSize){
            audioFFTBuffer[i] = data[i].toDouble()
        }

        for(i in audioFFTSize-zeroPaddingSize until audioFFTSize){
            audioFFTBuffer[i] = 0.0
        }
        //FFT実行
        audioFFT.realForward(audioFFTBuffer)

        // 処理結果の複素数配列から各周波数成分の振幅値を求めピーク分の要素番号を得る
        var maxAmp = 0.0
        var index = 0
        for (i in 0 until audioFFTSize/2) {
            var a = audioFFTBuffer[i*2] // 実部
            var b = audioFFTBuffer[i*2 + 1] // 虚部
            // a+ib の絶対値 √ a^2 + b^2 = r が振幅値
            spectrum[i] = sqrt(a*a + b*b)
            if (spectrum[i] > maxAmp) {
                maxAmp = spectrum[i]
                index = i
            }
        }
        // 要素番号・サンプリングレート・FFT サイズからピーク周波数を求める
//        return index * samplingRate / audioFFTSize

        var freqN:Double = index.toDouble()
        if(index > 0 && index < audioFFTSize/2-1){
            var dL:Double = spectrum[index-1]/spectrum[index]
            var dR:Double = spectrum[index+1]/spectrum[index]
            freqN += 0.5 * (dR*dR-dL*dL)
        }
        return freqN*samplingRate/audioFFTSize
    }

    private fun convertHertzToScale(hertz :Double): Double{
        return 12.0 * ln(hertz / 110.0) / ln(2.0)
    }

    private fun convertScaleToString(scale: Double): String{
        val precision = 2

        var s = scale.toInt()
        if(scale-s >= 0.5){
            s += 1
        }
        s *= precision

        val smod = s % (12 * precision)
        val soct = s / (12 * precision)

        var value: String=""

        when(smod) {
            0 -> value = "A"
            1 -> value = "A+"
            2 -> value = "A#"
            3 -> value = "A#+"
            4 -> value = "B"
            5 -> value = "B+"
            6 -> value = "C"
            7 -> value = "C+"
            8 -> value = "C#"
            9 -> value = "C#+"
            10 -> value = "D"
            11 -> value = "D+"
            12 -> value = "D#"
            13 -> value = "D#+"
            14 -> value = "E"
            15 -> value = "E+"
            16 -> value = "F"
            17 -> value = "F+"
            18 -> value = "F#"
            19 -> value = "F#+"
            20 -> value = "G"
            21 -> value = "G+"
            22 -> value = "G#"
            23 -> value = "G#+"
        }
        return value
    }

}
