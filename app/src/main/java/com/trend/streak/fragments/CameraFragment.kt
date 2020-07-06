package com.trend.streak.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.trend.streak.R
import android.hardware.display.DisplayManager
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.*
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.view.TextureViewMeteringPointFactory
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.adrino.hdr.Manager
import com.adrino.hdr.corehdr.Constants
import com.adrino.hdr.corehdr.CreateHDR
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.robertlevonyan.demo.camerax.utils.*
import com.trend.streak.analyzer.LuminosityAnalyzer
import com.trend.streak.camerax.utils.*
import com.trend.streak.databinding.FragmentCameraBinding
import com.trend.streak.enums.CameraTimer
import com.trend.streak.fragments.BaseFragment
import com.trend.streak.services.PortraitBlurService
import com.trend.streak.utils.FocusIndicatorView
import com.trend.streak.utils.SharedPrefsManager
import com.trend.streak.utils.SwipeGestureDetector
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import kotlin.properties.Delegates

class CameraFragment : BaseFragment<FragmentCameraBinding>(R.layout.fragment_camera) {
    companion object {
        private const val TAG = "CameraXDemo"
        const val KEY_FLASH = "sPrefFlashCamera"
        const val KEY_GRID = "sPrefGridCamera"
        const val KEY_HDR = "sPrefHDR"
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: SharedPrefsManager
    private lateinit var preview: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalyzer: ImageAnalysis
    private var displayId = -1
    private var lensFacing = CameraX.LensFacing.BACK
    private var flashMode by Delegates.observable(FlashMode.OFF.ordinal) { _, _, new ->
        binding.buttonFlash.setImageResource(
            when (new) {
                FlashMode.ON.ordinal -> R.drawable.ic_flash_on
                FlashMode.AUTO.ordinal -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
    }
    var final_image:File ? =null

    private var hasGrid = false
    private var hasHdr=false
    private var hasPortrait=false
    private var selectedTimer = CameraTimer.OFF

    /**
     * A display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                preview.setTargetRotation(view.display.rotation)
                imageCapture.setTargetRotation(view.display.rotation)
                imageAnalyzer.setTargetRotation(view.display.rotation)
            }
        } ?: Unit
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SharedPrefsManager.newInstance(requireContext())
        flashMode = prefs.getInt(KEY_FLASH, FlashMode.OFF.ordinal)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        hasHdr = prefs.getBoolean(KEY_HDR, false)
        if(hasHdr){
            binding.buttonHdr.setImageResource(R.drawable.ic_hdr_on)
        }else{binding.buttonHdr.setImageResource(R.drawable.ic_hdr_off)}
        initViews()

        displayManager =
            requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        binding.fragment = this // setting the variable for XML
        binding.viewFinder.addOnAttachStateChangeListener(object :
            View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) =
                displayManager.registerDisplayListener(displayListener, null)

            override fun onViewAttachedToWindow(v: View) =
                displayManager.unregisterDisplayListener(displayListener)
        })

        // This swipe gesture adds a fun gesture to switch between video and photo
        val swipeGestures = SwipeGestureDetector().apply {
            setSwipeCallback(right = {
                Navigation.findNavController(view).navigate(R.id.action_camera_to_video)
            })
        }
        val gestureDetectorCompat = GestureDetector(requireContext(), swipeGestures)
        view.setOnTouchListener { _, motionEvent ->
            if (gestureDetectorCompat.onTouchEvent(motionEvent)) return@setOnTouchListener false
            return@setOnTouchListener true
        }
    }

    /**
     * Create some initial states
     * */
    private fun initViews() {
        binding.buttonGrid.setImageResource(if (hasGrid) R.drawable.ic_grid_on else R.drawable.ic_grid_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        adjustInsets()
    }

    /**
     * This methods adds all necessary margins to some views based on window insets and screen orientation
     * */
    private fun adjustInsets() {
        binding.viewFinder.fitSystemWindows()
        binding.fabTakePicture.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                view.bottomMargin = windowInsets.systemWindowInsetBottom
            else view.endMargin = windowInsets.systemWindowInsetRight
        }
        binding.buttonTimer.onWindowInsets { view, windowInsets ->
            view.topMargin = windowInsets.systemWindowInsetTop
        }
    }

    /**
     * Change the facing of camera
     *  toggleButton() function is an Extension function made to animate button rotation
     * */
    @SuppressLint("RestrictedApi")
    fun toggleCamera() = binding.buttonSwitchCamera.toggleButton(
        lensFacing == CameraX.LensFacing.BACK, 180f,
        R.drawable.ic_outline_camera_rear, R.drawable.ic_outline_camera_front
    ) {
        lensFacing = if (it) CameraX.LensFacing.BACK else CameraX.LensFacing.FRONT

        CameraX.getCameraWithLensFacing(lensFacing)
        recreateCamera()
    }

    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    private fun recreateCamera() {
        CameraX.unbindAll()
        startCamera()
    }

    /**
     * Navigate to PreviewFragment
     * */
    fun openPreview() {
        if (!outputDirectory.listFiles().isNullOrEmpty())
            view?.let { Navigation.findNavController(it).navigate(R.id.action_camera_to_preview) }
    }

    /**
     * Show timer selection menu by circular reveal animation.
     *  circularReveal() function is an Extension function which is adding the circular reveal
     * */
    fun selectTimer() = binding.layoutTimerOptions.circularReveal(binding.buttonTimer)

    /**
     * This function is called from XML view via Data Binding to select a timer
     *  possible values are OFF, S3 or S10
     *  circularClose() function is an Extension function which is adding circular close
     * */
    fun closeTimerAndSelect(timer: CameraTimer) =
        binding.layoutTimerOptions.circularClose(binding.buttonTimer) {
            binding.buttonTimer.setImageResource(
                when (timer) {
                    CameraTimer.S3 -> {
                        selectedTimer = CameraTimer.S3
                        R.drawable.ic_timer_3
                    }
                    CameraTimer.S10 -> {
                        selectedTimer = CameraTimer.S10
                        R.drawable.ic_timer_10
                    }
                    else -> {
                        selectedTimer = CameraTimer.OFF
                        R.drawable.ic_timer_off
                    }
                }
            )
        }

    /**
     * Show flashlight selection menu by circular reveal animation.
     *  circularReveal() function is an Extension function which is adding the circular reveal
     * */
    fun selectFlash() = binding.layoutFlashOptions.circularReveal(binding.buttonFlash)

    /**
     * This function is called from XML view via Data Binding to select a FlashMode
     *  possible values are ON, OFF or AUTO
     *  circularClose() function is an Extension function which is adding circular close
     * */
    fun closeFlashAndSelect(flash: FlashMode) =
        binding.layoutFlashOptions.circularClose(binding.buttonFlash) {
            flashMode = when (flash) {
                FlashMode.ON -> FlashMode.ON.ordinal
                FlashMode.AUTO -> FlashMode.AUTO.ordinal
                else -> FlashMode.OFF.ordinal
            }
            prefs.putInt(KEY_FLASH, flashMode)
            imageCapture.flashMode = getFlashMode()
        }

    /**
     * Turns on or off the grid on the screen
     * */
    fun toggleGrid() {
        binding.buttonGrid.toggleButton(
            hasGrid,
            180f,
            R.drawable.ic_grid_off,
            R.drawable.ic_grid_on
        ) { flag ->
            hasGrid = flag
            prefs.putBoolean(KEY_GRID, flag)
            binding.groupGridLines.visibility = if (flag) View.VISIBLE else View.GONE
        }
    }

    /**
     * Turns on or off the HDR if available
     * */
    fun toggleHdr() {
        binding.buttonHdr.toggleButton(
            hasHdr,
            360f,
            R.drawable.ic_hdr_off,
            R.drawable.ic_hdr_on
        ) { flag ->
            hasHdr = flag
            prefs.putBoolean(KEY_HDR, flag)
            recreateCamera()
        }
    }
    fun togglePortrait() {
        binding.buttonPortrait.toggleButton(
            hasPortrait,
            360f,
            R.drawable.ic_portrait_off,
            R.drawable.ic_portrait_on
        ) { flag ->
            hasPortrait= flag
            recreateCamera()
        }
    }

    override fun onPermissionGranted() {
        // Each time apps is coming to foreground the need permission check is being processed
        binding.viewFinder.let { vf ->
            vf.post {
                // Setting current display ID
                displayId = vf.display.displayId
                recreateCamera()
                lifecycleScope.launch(Dispatchers.IO) {
                    // Do on IO Dispatcher
                    // Check if there are any photos or videos in the app directory and preview the last one
                    outputDirectory.listFiles()?.firstOrNull()?.let {
                        setGalleryThumbnail(it)
                    }
                        ?: binding.buttonGallery.setImageResource(R.drawable.ic_no_picture) // or the default placeholder
                }
            }
        }
    }

    private fun startCamera() {
        // This is the Texture View where the camera will be rendered
        val viewFinder = binding.viewFinder

        // The ratio for the output image and preview
        val ratio = AspectRatio.RATIO_4_3

        // The Configuration of how we want to preview the camera
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(ratio) // setting the aspect ration
            setLensFacing(lensFacing) // setting the lens facing (front or back)
            setTargetRotation(viewFinder.display.rotation) // setting the rotation of the camera
        }.build()

        // Create an instance of Camera Preview
        preview = AutoFitPreviewBuilder.build(previewConfig, viewFinder)

        // The Configuration of how we want to capture the image
        val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
            setTargetAspectRatio(ratio) // setting the aspect ration
            setLensFacing(lensFacing) // setting the lens facing (front or back)
            setCaptureMode(ImageCapture.CaptureMode.MAX_QUALITY) // setting to have pictures with highest quality possible
            setTargetRotation(viewFinder.display.rotation) // setting the rotation of the camera
            setFlashMode(getFlashMode()) // setting the flash
        }

        imageCapture = ImageCapture(imageCaptureConfig.build())
        binding.imageCapture = imageCapture
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
        }.build()

        // Create an Image Analyzer Use Case instance for luminosity analysis
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            // Use a worker thread for image analysis to prevent glitches
            val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
            setAnalyzer(ThreadExecutor(Handler(analyzerThread.looper)), LuminosityAnalyzer())
        }
        val cameraControl = CameraX.getCameraControl(lensFacing)
        val cameraInfo=CameraX.getCameraInfo(lensFacing)
        setUpTapToFocus(viewFinder,cameraControl,binding.afIndicator)
        // Check for lens facing to add or not the Image Analyzer Use Case
        if (lensFacing == CameraX.LensFacing.BACK) {
            CameraX.bindToLifecycle(viewLifecycleOwner, preview, imageCapture, analyzerUseCase)
        } else {
            CameraX.bindToLifecycle(viewLifecycleOwner, preview, imageCapture)
        }
    }
    private  fun setUpTapToFocus(view:TextureView,cameraControl: CameraControl,mFocusIndicaor: FocusIndicatorView){

        view.setOnTouchListener { _, event ->

Log.e("mad","tap to focus")

            val factory = TextureViewMeteringPointFactory(view)
            val point = factory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder.from(point).build()
            cameraControl.startFocusAndMetering(action)
            mFocusIndicaor.clearAnimation()
            mFocusIndicaor.visibility = View.VISIBLE
            mFocusIndicaor.alpha = 1.0f


            val lp = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(event.x.toInt(), event.y.toInt() , 0, 0)
           binding.afIndicator.setLocation(event.x,event.y)
            mFocusIndicaor.animate().alpha(0.0f).setDuration(5000)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        mFocusIndicaor.visibility = View.GONE
                    }
                })
            return@setOnTouchListener true
        }

    }


    private fun getFlashMode() = when (flashMode) {
        FlashMode.ON.ordinal -> FlashMode.ON
        FlashMode.AUTO.ordinal -> FlashMode.AUTO
        else -> FlashMode.OFF
    }

    @Suppress("NON_EXHAUSTIVE_WHEN")
    fun takePicture(imageCapture: ImageCapture) = lifecycleScope.launch(Dispatchers.Main) {
        // Show a timer based on user selection
        when (selectedTimer) {
            CameraTimer.S3 -> for (i in 3 downTo 1) {
                binding.textCountDown.text = i.toString()
                delay(1000)
            }
            CameraTimer.S10 -> for (i in 10 downTo 1) {
                binding.textCountDown.text = i.toString()
                delay(1000)
            }
        }
        binding.textCountDown.text = ""
imageCaptureSeqeunce(imageCapture)
    }

    private fun captureImage(imageCapture: ImageCapture) {
        // Create the output file
        val imageFile = File(outputDirectory, "${System.currentTimeMillis()}.jpg")
        // Capture the image, first parameter is the file where the image should be stored, the second parameter is the callback after taking a photo
        imageCapture.takePicture(
            imageFile,
            requireContext().mainExecutor(),
            object : ImageCapture.OnImageSavedListener {
                override fun onImageSaved(file: File) { // the resulting file of taken photo
                    // Create small preview
                    setGalleryThumbnail(file)
                    val msg = "Photo saved in ${file.absolutePath}"
                    Log.d("CameraXDemo", msg)
                }

                override fun onError(
                    imageCaptureError: ImageCapture.ImageCaptureError,
                    message: String,
                    cause: Throwable?
                ) {
                    // This function is called if there is some error during capture process
                    val msg = "Photo capture failed: $message"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    Log.e("CameraXApp", msg)
                    cause?.printStackTrace()
                }
            })
    }

    private fun setGalleryThumbnail(file: File) = binding.buttonGallery.let {
        // Do the work on view's thread, this is needed, because the function is called in a Coroutine Scope's IO Dispatcher
        it.post {
            Glide.with(requireContext())
                .load(file)
                .apply(RequestOptions.circleCropTransform())
                .into(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
    }
    fun imageCaptureSeqeunce(imageCapture: ImageCapture){
        if(hasHdr){
            Log.e("CameraXApp", "Hdr Start")
            Thread ({
                Log.e("CameraXApp", "Hdr Start thread")
                   var file=startHdrCapture(imageCapture)
                    if(file!=null)
                        setGalleryThumbnail(file)



            }).start()


        }else if(hasPortrait){
        takePortrait(imageCapture)
        }
         else if(hasPortrait and hasHdr){
            var file=startHdrCapture(imageCapture)
            if(file!=null){
            val intent = Intent(context, PortraitBlurService::class.java)
            intent.putExtra("path", file.absolutePath)
            requireContext().startService(intent)}


        }
        else{
            captureImage(imageCapture)
        }






    }

    fun takePortrait(imageCapture: ImageCapture): File {

        var imageFile = File(outputDirectory, "${System.currentTimeMillis()}.jpg")
        // Capture the image, first parameter is the file where the image should be stored, the second parameter is the callback after taking a photo
        imageCapture.takePicture(
            imageFile,
            requireContext().mainExecutor(),
            object : ImageCapture.OnImageSavedListener {
                override fun onImageSaved(file: File) { // the resulting file of taken photo
                    val intent = Intent(context, PortraitBlurService::class.java)
                    intent.putExtra("path", file.absolutePath)
                    requireContext().startService(intent)
                }

                override fun onError(imageCaptureError: ImageCapture.ImageCaptureError,message: String,cause: Throwable?) {

                }
            })
        return imageFile
    }
    fun startHdrCapture(imageCapture: ImageCapture): File? {
        Log.e("CameraXApp", "Hdr Start capture")
        var pictureCount=0
        var acquired=0
        while (pictureCount<4){

            if(getImage("pic${pictureCount}.jpg",imageCapture)){
                acquired++
            }
            pictureCount++
        }
        Log.e("CameraXApp", "Hdr capture ${acquired}")
        if(acquired==3) {


        }else{
            Log.e("CameraXApp", "Hdr Start ERROR")
        }
        Log.e("CameraXApp", "Hdr Start cmethod_call")
return null
    }
    private fun loadImages(file: File, deleteImages: Boolean): List<Bitmap> {
        val bmpImageList = ArrayList<Bitmap>(Constants.INPUT_IMAGE_SIZE)

        for (imgIndex in 1..Constants.INPUT_IMAGE_SIZE) {

            // Load Images
            val imgFile = File(file, "pic$imgIndex.jpg")
            bmpImageList.add(BitmapFactory.decodeFile(imgFile.absolutePath))

            // Delete Images if required
            if (deleteImages && imgFile.delete()) {
                Log.e(TAG, "loadImages: Image Deleted $imgIndex")
            }
        }
        return bmpImageList
    }
    fun getImage(pictureCount:String ,imageCapture: ImageCapture): Boolean {
        var success=false
        val mainExecutor = ContextCompat.getMainExecutor(requireContext())
        val imageFile=File(outputDirectory,pictureCount)
        imageCapture.takePicture(
            imageFile,
            mainExecutor,
            object : ImageCapture.OnImageSavedListener {
                override fun onImageSaved(file: File) {

                    Log.e(TAG, "Capture${pictureCount}")
                      if(pictureCount=="pic3.jpg"){
                          val imageFile = File(outputDirectory, "${System.currentTimeMillis()}hdr.jpg")
                          val hdrManager = Manager(requireContext())
                          var image =
                              hdrManager.perform(loadImages(outputDirectory, true), CreateHDR.Actions.HDR)
                          final_image = File(outputDirectory, "pic0.jpg")
                          var originalImageExif = ExifInterface(final_image?.absolutePath)
                          var origImageOrientation =
                              originalImageExif.getAttribute(ExifInterface.TAG_ORIENTATION)
                          var out: FileOutputStream? = null


                          if (image != null) {

                              out = FileOutputStream(imageFile)
                              image[0].compress(Bitmap.CompressFormat.JPEG, 100, out)
                              out.close()
                          }
                          val exifForNewImage = ExifInterface(imageFile.absolutePath)
                          exifForNewImage.setAttribute(ExifInterface.TAG_ORIENTATION, origImageOrientation)
                          exifForNewImage.saveAttributes()
                          setGalleryThumbnail(imageFile)

                      }

                }

                override fun onError(imageCaptureError: ImageCapture.ImageCaptureError, message: String, cause: Throwable?) {
                    Log.e(TAG, "Couldnt Capture${pictureCount}")
                }
            })
return success
    }

    override fun onBackPressed() {
        when {
            binding.layoutTimerOptions.visibility == View.VISIBLE -> binding.layoutTimerOptions.circularClose(
                binding.buttonTimer
            )
            binding.layoutFlashOptions.visibility == View.VISIBLE -> binding.layoutFlashOptions.circularClose(
                binding.buttonFlash
            )
            else -> requireActivity().finish()
        }
    }
}