package com.goodwy.commons.activities

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.RecoverableSecurityException
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.util.Pair
import androidx.core.view.ScrollingView
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.R
import com.goodwy.commons.asynctasks.CopyMoveTask
import com.goodwy.commons.dialogs.*
import com.goodwy.commons.dialogs.WritePermissionDialog.Mode
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.interfaces.CopyMoveListener
import com.goodwy.commons.models.FAQItem
import com.goodwy.commons.models.FileDirItem
import com.goodwy.commons.views.MySearchMenu
import com.google.android.material.appbar.AppBarLayout
import java.io.File
import java.io.OutputStream
import java.util.regex.Pattern

abstract class BaseSimpleActivity : AppCompatActivity() {
    var materialScrollColorAnimation: ValueAnimator? = null
    var copyMoveCallback: ((destinationPath: String) -> Unit)? = null
    var actionOnPermission: ((granted: Boolean) -> Unit)? = null
    var isAskingPermissions = false
    var useDynamicTheme = true
    var showTransparentTop = false      // TODO Theme bar top color
    var isMaterialActivity = false      // by material activity we mean translucent navigation bar and opaque status and action bars
    var updateNavigationBarColor = true
    var checkedDocumentPath = ""
    var currentScrollY = 0
    var configItemsToExport = LinkedHashMap<String, Any>()

    private var mainCoordinatorLayout: CoordinatorLayout? = null
    private var nestedView: View? = null
    var scrollingView: ScrollingView? = null
    private var toolbar: Toolbar? = null
    private var mySearchMenu: MySearchMenu? = null
    private var useTransparentNavigation = false
    private var useTopSearchMenu = false
    private val GENERIC_PERM_HANDLER = 100
    private val DELETE_FILE_SDK_30_HANDLER = 300
    private val RECOVERABLE_SECURITY_HANDLER = 301
    private val UPDATE_FILE_SDK_30_HANDLER = 302
    private val MANAGE_MEDIA_RC = 303
    private val TRASH_FILE_SDK_30_HANDLER = 304

    companion object {
        var funAfterSAFPermission: ((success: Boolean) -> Unit)? = null
        var funAfterSdk30Action: ((success: Boolean) -> Unit)? = null
        var funAfterUpdate30File: ((success: Boolean) -> Unit)? = null
        var funAfterTrash30File: ((success: Boolean) -> Unit)? = null
        var funRecoverableSecurity: ((success: Boolean) -> Unit)? = null
        var funAfterManageMediaPermission: (() -> Unit)? = null
    }

    abstract fun getAppIconIDs(): ArrayList<Int>

    abstract fun getAppLauncherName(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        if (useDynamicTheme) {
            setTheme(getThemeId(showTransparentTop = showTransparentTop))
        }

        super.onCreate(savedInstanceState)
        if (!packageName.startsWith("com.goodwy.", true)) {
            if ((0..50).random() == 10 || baseConfig.appRunCount % 100 == 0) {
                val label = "You are using a fake version of the app. For your own safety download the original one from play.google.com. Thanks"
                ConfirmationDialog(this, label, positive = R.string.ok, negative = 0) {
                    launchMoreAppsFromUsIntent()
                }
            }
        }
    }

    @SuppressLint("NewApi")
    override fun onResume() {
        super.onResume()
        if (useDynamicTheme) {
            setTheme(getThemeId(showTransparentTop = showTransparentTop))

            val backgroundColor = if (baseConfig.isUsingSystemTheme) {
                resources.getColor(R.color.you_background_color, theme)
            } else {
                baseConfig.backgroundColor
            }

            updateBackgroundColor(backgroundColor)
        }

        // if enabled, then when disabling the top bar in the light theme can not see the status bar icons, you need to put a shadow?
        if (showTransparentTop) {
            window.statusBarColor = Color.TRANSPARENT
        } else if (!isMaterialActivity) {
                val color = if (baseConfig.isUsingSystemTheme) {
                    resources.getColor(R.color.you_status_bar_color)
                } else {
                    getProperBackgroundColor() //getProperStatusBarColor()
                }

                updateActionbarColor(color)
        }
        //updateRecentsAppIcon()

        if (updateNavigationBarColor) {
            var navBarColor = getProperBackgroundColor()
            if (isMaterialActivity) {
                navBarColor = navBarColor.adjustAlpha(ZERO_ALPHA) //HIGHER_ALPHA
            }

            updateNavigationBarColor(navBarColor)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        funAfterSAFPermission = null
        actionOnPermission = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleNavigationAndScrolling()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                hideKeyboard()
                finish()
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        if (newBase.baseConfig.useEnglish && !isTiramisuPlus()) {
            super.attachBaseContext(MyContextWrapper(newBase).wrap(newBase, "en"))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    fun updateBackgroundColor(color: Int = baseConfig.backgroundColor) {
        window.decorView.setBackgroundColor(color)
    }

    fun updateStatusbarColor(color: Int) {
        window.statusBarColor = color // TODO Status Bar Color

        if (color.getContrastColor() == DARK_GREY) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.addBit(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.removeBit(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
    }

    //TODO actionbar color
    fun updateActionbarColor(color: Int = getProperBackgroundColor()) { //getProperStatusBarColor()
        //supportActionBar?.setBackgroundDrawable(ColorDrawable(color))
        supportActionBar?.elevation = 0F //TODO actionbar shadow
        //updateActionBarTitle(supportActionBar?.title.toString(), color)
        updateStatusbarColor(color)
        setTaskDescription(ActivityManager.TaskDescription(null, null, color))
    }

    fun updateNavigationBarColor(color: Int) { //baseConfig.navigationBarColor
        window.navigationBarColor = color
        updateNavigationBarButtons(color)
    }

    fun updateNavigationBarButtons(color: Int) {
        if (isOreoPlus()) {
            if (color.getContrastColor() == DARK_GREY) {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.addBit(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            } else {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.removeBit(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
        }
    }

    // use translucent navigation bar, set the background color to action and status bars
    fun updateMaterialActivityViews(
        mainCoordinatorLayout: CoordinatorLayout?,
        nestedView: View?,
        useTransparentNavigation: Boolean,
        useTopSearchMenu: Boolean
    ) {
        this.mainCoordinatorLayout = mainCoordinatorLayout
        this.nestedView = nestedView
        this.useTransparentNavigation = useTransparentNavigation
        this.useTopSearchMenu = useTopSearchMenu
        handleNavigationAndScrolling()

        val backgroundColor = getProperBackgroundColor()
        updateStatusbarColor(backgroundColor)
        updateActionbarColor(backgroundColor)
    }

    private fun handleNavigationAndScrolling() {
        if (useTransparentNavigation) {
            if (navigationBarHeight > 0 || isUsingGestureNavigation()) {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.addBit(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                updateTopBottomInsets(statusBarHeight, navigationBarHeight)
                // Don't touch this. Window Inset API often has a domino effect and things will most likely break.
                onApplyWindowInsets {
                    val insets = it.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                    updateTopBottomInsets(insets.top, insets.bottom)
                }
            } else {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility.removeBit(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                updateTopBottomInsets(0, 0)
            }
        }
    }

    private fun updateTopBottomInsets(top: Int, bottom: Int) {
        nestedView?.run {
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)
        }
        (mainCoordinatorLayout?.layoutParams as? FrameLayout.LayoutParams)?.topMargin = top
    }

    // colorize the top toolbar and statusbar at scrolling down a bit
    fun setupMaterialScrollListener(scrollingView: ScrollingView?, toolbar: Toolbar) {
        this.scrollingView = scrollingView
        this.toolbar = toolbar
        if (scrollingView is RecyclerView) {
            scrollingView.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                val newScrollY = scrollingView.computeVerticalScrollOffset()
                scrollingChanged(newScrollY, currentScrollY)
                currentScrollY = newScrollY
            }
        } else if (scrollingView is NestedScrollView) {
            scrollingView.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                scrollingChanged(scrollY, oldScrollY)
            }
        }
    }

    fun setupSearchMenuScrollListener(scrollingView: ScrollingView?, searchMenu: MySearchMenu) {
        this.scrollingView = scrollingView
        this.mySearchMenu = searchMenu
        if (scrollingView is RecyclerView) {
            scrollingView.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                val newScrollY = scrollingView.computeVerticalScrollOffset()
                scrollingChanged(newScrollY, currentScrollY, true)
                currentScrollY = newScrollY
            }
        } else if (scrollingView is NestedScrollView) {
            scrollingView.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                scrollingChanged(scrollY, oldScrollY, true)
            }
        }
    }

    private fun scrollingChanged(newScrollY: Int, oldScrollY: Int, isMySearchMenu: Boolean = false) {
        if (newScrollY > 0 && oldScrollY == 0) {
            val colorFrom = window.statusBarColor
            val colorTo = getColoredMaterialStatusBarColor()
            if (isMySearchMenu) animateMySearchMenuColors(colorFrom, colorTo)
            else  animateTopBarColors(colorFrom, colorTo)
        } else if (newScrollY == 0 && oldScrollY > 0) {
            val colorFrom = window.statusBarColor
            val colorTo = getRequiredStatusBarColor()
            if (isMySearchMenu) animateMySearchMenuColors(colorFrom, colorTo)
            else animateTopBarColors(colorFrom, colorTo)
        }
    }

    fun animateTopBarColors(colorFrom: Int, colorTo: Int) {
        if (toolbar == null) {
            return
        }

        materialScrollColorAnimation?.end()
        materialScrollColorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        materialScrollColorAnimation!!.addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            if (toolbar != null) {
                updateTopBarColors(toolbar!!, color)
            }
        }

        materialScrollColorAnimation!!.start()
    }

    fun animateMySearchMenuColors(colorFrom: Int, colorTo: Int) {
        if (mySearchMenu == null) {
            return
        }

        materialScrollColorAnimation?.end()
        materialScrollColorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        materialScrollColorAnimation!!.addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            if (mySearchMenu != null) {
                mySearchMenu!!.updateColors(color, scrollingView?.computeVerticalScrollOffset() ?: 0)
            }
        }

        materialScrollColorAnimation!!.start()
    }

    fun getRequiredStatusBarColor(): Int {
        return if ((scrollingView is RecyclerView || scrollingView is NestedScrollView) && scrollingView?.computeVerticalScrollOffset() == 0) {
            getProperBackgroundColor()
        } else {
            getColoredMaterialStatusBarColor()
        }
    }

    fun updateTopBarColors(toolbar: Toolbar, colorBackground: Int, colorPrimary: Int = getProperPrimaryColor(), useColorForStatusBar: Boolean = true) {
        val getProperBackgroundColor = getProperBackgroundColor()
        val contrastColor = if (colorBackground == Color.TRANSPARENT) getProperBackgroundColor.getContrastColor() else colorBackground.getContrastColor()
        val itemColor = if (baseConfig.topAppBarColorIcon) colorPrimary else contrastColor
        val titleColor = if (baseConfig.topAppBarColorTitle) colorPrimary else contrastColor

        //if (!useTopSearchMenu) {
        val statusBarColor = if (useColorForStatusBar) colorBackground else getProperBackgroundColor
        updateStatusbarColor(statusBarColor) // colorBackground
        toolbar.setBackgroundColor(colorBackground)
        toolbar.setTitleTextColor(titleColor)
        toolbar.navigationIcon?.applyColorFilter(itemColor)
        toolbar.collapseIcon = resources.getColoredDrawableWithColor(this, R.drawable.ic_chevron_left_vector, itemColor)
        //}
        val overflowIconRes = getOverflowIcon(baseConfig.overflowIcon)
        toolbar.overflowIcon = resources.getColoredDrawableWithColor(this, overflowIconRes, itemColor)

        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            try {
                menu.getItem(i)?.icon?.setTint(itemColor)
            } catch (ignored: Exception) {
            }
        }
    }

    fun updateStatusBarOnPageChange() {
        if (scrollingView is RecyclerView || scrollingView is NestedScrollView) {
            val scrollY = scrollingView!!.computeVerticalScrollOffset()
            val colorFrom = window.statusBarColor
            val colorTo = if (scrollY > 0) {
                getColoredMaterialStatusBarColor()
            } else {
                getRequiredStatusBarColor()
            }
            animateTopBarColors(colorFrom, colorTo)
            currentScrollY = scrollY
        }
    }

    @SuppressLint("ObjectAnimatorBinding")
    fun setupToolbar(
        toolbar: Toolbar,
        toolbarNavigationIcon: NavigationIcon = NavigationIcon.None,
        statusBarColor: Int = getRequiredStatusBarColor(),
        searchMenuItem: MenuItem? = null,
        appBarLayout: AppBarLayout? = null,
        navigationClick: Boolean = true
    ) {
        val contrastColor = statusBarColor.getContrastColor()
        if (toolbarNavigationIcon != NavigationIcon.None) {
            val drawableId = if (toolbarNavigationIcon == NavigationIcon.Cross) R.drawable.ic_cross_vector else R.drawable.ic_chevron_left_vector
            toolbar.navigationIcon = resources.getColoredDrawableWithColor(this, drawableId, contrastColor)
            toolbar.setNavigationContentDescription(toolbarNavigationIcon.accessibilityResId)
        }

        if (navigationClick) {
            toolbar.setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }

        updateTopBarColors(toolbar, statusBarColor)

        if (!useTopSearchMenu) {
            searchMenuItem?.actionView?.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)?.apply {
                applyColorFilter(contrastColor)
            }

            searchMenuItem?.actionView?.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
                setTextColor(contrastColor)
                setHintTextColor(contrastColor.adjustAlpha(MEDIUM_ALPHA))
                hint = "${getString(R.string.search)}…"

                if (isQPlus()) {
                    textCursorDrawable = null
                }
            }

            // search underline
            searchMenuItem?.actionView?.findViewById<View>(androidx.appcompat.R.id.search_plate)?.apply {
                background.setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.MULTIPLY)
            }
        }

        if (appBarLayout != null) {
            val stateListAnimator = StateListAnimator()
            stateListAnimator.addState(
                IntArray(0),
                ObjectAnimator.ofFloat(appBarLayout, "elevation", 0.0f)
            )
            appBarLayout.stateListAnimator = stateListAnimator
        }
    }

    fun updateRecentsAppIcon() {
        if (baseConfig.isUsingModifiedAppIcon) {
            val appIconIDs = getAppIconIDs()
            val currentAppIconColorIndex = getCurrentAppIconColorIndex()
            if (appIconIDs.size - 1 < currentAppIconColorIndex) {
                return
            }

            val recentsIcon = BitmapFactory.decodeResource(resources, appIconIDs[currentAppIconColorIndex])
            val title = getAppLauncherName()
            val color = baseConfig.primaryColor

            val description = ActivityManager.TaskDescription(title, recentsIcon, color)
            setTaskDescription(description)
        }
    }

    fun updateMenuItemColors(menu: Menu?, baseColor: Int = getProperBackgroundColor() /*getProperStatusBarColor()*/, forceWhiteIcons: Boolean = false,
                             noContrastColor: Boolean = false) {
        if (menu == null) {
            return
        }

        // TODO ACTIONBAR ICON COLOR
        var color = if (noContrastColor) baseColor else baseColor.getContrastColor()
        if (forceWhiteIcons) {
            color = Color.WHITE
        }
        if (baseConfig.topAppBarColorIcon && !forceWhiteIcons) color = getProperPrimaryColor()

        for (i in 0 until menu.size()) {
            try {
                menu.getItem(i)?.icon?.setTint(color)
            } catch (ignored: Exception) {
            }
        }

        //val drawableId = if (useCrossAsBack) R.drawable.ic_chevron_left_vector else R.drawable.ic_chevron_left_vector //ic_arrow_left_vector
        //val icon = resources.getColoredDrawableWithColor(drawableId, color)
        //supportActionBar?.setHomeAsUpIndicator(icon)
    }

    private fun getCurrentAppIconColorIndex(): Int {
        val appIconColor = baseConfig.appIconColor
        getAppIconColors().forEachIndexed { index, color ->
            if (color == appIconColor) {
                return index
            }
        }
        return 0
    }

    fun setTranslucentNavigation() {
        window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        val partition = try {
            checkedDocumentPath.substring(9, 18)
        } catch (e: Exception) {
            ""
        }

        val sdOtgPattern = Pattern.compile(SD_OTG_SHORT)

        if (requestCode == CREATE_DOCUMENT_SDK_30) {
            if (resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {

                val treeUri = resultData.data
                val checkedUri = buildDocumentUriSdk30(checkedDocumentPath)

                if (treeUri != checkedUri) {
                    toast(getString(R.string.wrong_folder_selected, checkedDocumentPath))
                    return
                }

                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                applicationContext.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                val funAfter = funAfterSdk30Action
                funAfterSdk30Action = null
                funAfter?.invoke(true)
            } else {
                funAfterSdk30Action?.invoke(false)
            }

        } else if (requestCode == OPEN_DOCUMENT_TREE_FOR_SDK_30) {
            if (resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
                val treeUri = resultData.data
                val checkedUri = createFirstParentTreeUri(checkedDocumentPath)

                if (treeUri != checkedUri) {
                    val level = getFirstParentLevel(checkedDocumentPath)
                    val firstParentPath = checkedDocumentPath.getFirstParentPath(this, level)
                    toast(getString(R.string.wrong_folder_selected, humanizePath(firstParentPath)))
                    return
                }

                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                applicationContext.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                val funAfter = funAfterSdk30Action
                funAfterSdk30Action = null
                funAfter?.invoke(true)
            } else {
                funAfterSdk30Action?.invoke(false)
            }

        } else if (requestCode == OPEN_DOCUMENT_TREE_FOR_ANDROID_DATA_OR_OBB) {
            if (resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
                if (isProperAndroidRoot(checkedDocumentPath, resultData.data!!)) {
                    if (resultData.dataString == baseConfig.OTGTreeUri || resultData.dataString == baseConfig.sdTreeUri) {
                        val pathToSelect = createAndroidDataOrObbPath(checkedDocumentPath)
                        toast(getString(R.string.wrong_folder_selected, pathToSelect))
                        return
                    }

                    val treeUri = resultData.data
                    storeAndroidTreeUri(checkedDocumentPath, treeUri.toString())

                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    applicationContext.contentResolver.takePersistableUriPermission(treeUri!!, takeFlags)
                    funAfterSAFPermission?.invoke(true)
                    funAfterSAFPermission = null
                } else {
                    toast(getString(R.string.wrong_folder_selected, createAndroidDataOrObbPath(checkedDocumentPath)))
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        if (isRPlus()) {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, createAndroidDataOrObbUri(checkedDocumentPath))
                        }

                        try {
                            startActivityForResult(this, requestCode)
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    }
                }
            } else {
                funAfterSAFPermission?.invoke(false)
            }
        } else if (requestCode == OPEN_DOCUMENT_TREE_SD) {
            if (resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
                val isProperPartition = partition.isEmpty() || !sdOtgPattern.matcher(partition).matches() || (sdOtgPattern.matcher(partition)
                    .matches() && resultData.dataString!!.contains(partition))
                if (isProperSDRootFolder(resultData.data!!) && isProperPartition) {
                    if (resultData.dataString == baseConfig.OTGTreeUri) {
                        toast(R.string.sd_card_usb_same)
                        return
                    }

                    saveTreeUri(resultData)
                    funAfterSAFPermission?.invoke(true)
                    funAfterSAFPermission = null
                } else {
                    toast(R.string.wrong_root_selected)
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

                    try {
                        startActivityForResult(intent, requestCode)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            } else {
                funAfterSAFPermission?.invoke(false)
            }
        } else if (requestCode == OPEN_DOCUMENT_TREE_OTG) {
            if (resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
                val isProperPartition = partition.isEmpty() || !sdOtgPattern.matcher(partition).matches() || (sdOtgPattern.matcher(partition)
                    .matches() && resultData.dataString!!.contains(partition))
                if (isProperOTGRootFolder(resultData.data!!) && isProperPartition) {
                    if (resultData.dataString == baseConfig.sdTreeUri) {
                        funAfterSAFPermission?.invoke(false)
                        toast(R.string.sd_card_usb_same)
                        return
                    }
                    baseConfig.OTGTreeUri = resultData.dataString!!
                    baseConfig.OTGPartition = baseConfig.OTGTreeUri.removeSuffix("%3A").substringAfterLast('/').trimEnd('/')
                    updateOTGPathFromPartition()

                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    applicationContext.contentResolver.takePersistableUriPermission(resultData.data!!, takeFlags)

                    funAfterSAFPermission?.invoke(true)
                    funAfterSAFPermission = null
                } else {
                    toast(R.string.wrong_root_selected_usb)
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

                    try {
                        startActivityForResult(intent, requestCode)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            } else {
                funAfterSAFPermission?.invoke(false)
            }
        } else if (requestCode == SELECT_EXPORT_SETTINGS_FILE_INTENT && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
            val outputStream = contentResolver.openOutputStream(resultData.data!!)
            exportSettingsTo(outputStream, configItemsToExport)
        } else if (requestCode == DELETE_FILE_SDK_30_HANDLER) {
            funAfterSdk30Action?.invoke(resultCode == Activity.RESULT_OK)
        } else if (requestCode == RECOVERABLE_SECURITY_HANDLER) {
            funRecoverableSecurity?.invoke(resultCode == Activity.RESULT_OK)
            funRecoverableSecurity = null
        } else if (requestCode == UPDATE_FILE_SDK_30_HANDLER) {
            funAfterUpdate30File?.invoke(resultCode == Activity.RESULT_OK)
        } else if (requestCode == MANAGE_MEDIA_RC) {
            funAfterManageMediaPermission?.invoke()
        } else if (requestCode == TRASH_FILE_SDK_30_HANDLER) {
            funAfterTrash30File?.invoke(resultCode == Activity.RESULT_OK)
        }
    }

    private fun saveTreeUri(resultData: Intent) {
        val treeUri = resultData.data
        baseConfig.sdTreeUri = treeUri.toString()

        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        applicationContext.contentResolver.takePersistableUriPermission(treeUri!!, takeFlags)
    }

    private fun isProperSDRootFolder(uri: Uri) = isExternalStorageDocument(uri) && isRootUri(uri) && !isInternalStorage(uri)
    private fun isProperSDFolder(uri: Uri) = isExternalStorageDocument(uri) && !isInternalStorage(uri)

    private fun isProperOTGRootFolder(uri: Uri) = isExternalStorageDocument(uri) && isRootUri(uri) && !isInternalStorage(uri)
    private fun isProperOTGFolder(uri: Uri) = isExternalStorageDocument(uri) && !isInternalStorage(uri)

    private fun isRootUri(uri: Uri) = uri.lastPathSegment?.endsWith(":") ?: false

    private fun isInternalStorage(uri: Uri) = isExternalStorageDocument(uri) && DocumentsContract.getTreeDocumentId(uri).contains("primary")
    private fun isAndroidDir(uri: Uri) = isExternalStorageDocument(uri) && DocumentsContract.getTreeDocumentId(uri).contains(":Android")
    private fun isInternalStorageAndroidDir(uri: Uri) = isInternalStorage(uri) && isAndroidDir(uri)
    private fun isOTGAndroidDir(uri: Uri) = isProperOTGFolder(uri) && isAndroidDir(uri)
    private fun isSDAndroidDir(uri: Uri) = isProperSDFolder(uri) && isAndroidDir(uri)
    private fun isExternalStorageDocument(uri: Uri) = EXTERNAL_STORAGE_PROVIDER_AUTHORITY == uri.authority

    private fun isProperAndroidRoot(path: String, uri: Uri): Boolean {
        return when {
            isPathOnOTG(path) -> isOTGAndroidDir(uri)
            isPathOnSD(path) -> isSDAndroidDir(uri)
            else -> isInternalStorageAndroidDir(uri)
        }
    }

    fun startAboutActivity(appNameId: Int, licenseMask: Long, versionName: String,
                           faqItems: ArrayList<FAQItem>, showFAQBeforeMail: Boolean, licensingKey: String,
                           productIdX1: String, productIdX2: String, productIdX3: String,
                           subscriptionIdX1: String, subscriptionIdX2: String, subscriptionIdX3: String,
                           playStoreInstalled: Boolean = true,
                           ruStoreInstalled: Boolean = false,) {
        hideKeyboard()
        Intent(applicationContext, AboutActivity::class.java).apply {
            putExtra(APP_ICON_IDS, getAppIconIDs())
            putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
            putExtra(APP_NAME, getString(appNameId))
            putExtra(APP_LICENSES, licenseMask)
            putExtra(APP_VERSION_NAME, versionName)
            putExtra(APP_FAQ, faqItems)
            putExtra(SHOW_FAQ_BEFORE_MAIL, showFAQBeforeMail)
            //Goodwy
            putExtra(GOOGLE_PLAY_LICENSING_KEY, licensingKey)
            putExtra(PRODUCT_ID_X1, productIdX1)
            putExtra(PRODUCT_ID_X2, productIdX2)
            putExtra(PRODUCT_ID_X3, productIdX3)
            putExtra(SUBSCRIPTION_ID_X1, subscriptionIdX1)
            putExtra(SUBSCRIPTION_ID_X2, subscriptionIdX2)
            putExtra(SUBSCRIPTION_ID_X3, subscriptionIdX3)
            putExtra(PLAY_STORE_INSTALLED, playStoreInstalled)
            putExtra(RU_STORE, ruStoreInstalled)
            startActivity(this)
        }
    }

    fun startPurchaseActivity(appNameId: Int, licensingKey: String,
                              productIdX1: String, productIdX2: String, productIdX3: String,
                              subscriptionIdX1: String, subscriptionIdX2: String, subscriptionIdX3: String,
                              showLifebuoy: Boolean = resources.getBoolean(R.bool.show_lifebuoy),
                              playStoreInstalled: Boolean = true,
                              ruStoreInstalled: Boolean = false,
                              showCollection: Boolean = resources.getBoolean(R.bool.show_collection)) {
        Intent(applicationContext, PurchaseActivity::class.java).apply {
            putExtra(APP_ICON_IDS, getAppIconIDs())
            putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
            putExtra(APP_NAME, getString(appNameId))
            putExtra(GOOGLE_PLAY_LICENSING_KEY, licensingKey)
            putExtra(PRODUCT_ID_X1, productIdX1)
            putExtra(PRODUCT_ID_X2, productIdX2)
            putExtra(PRODUCT_ID_X3, productIdX3)
            putExtra(SUBSCRIPTION_ID_X1, subscriptionIdX1)
            putExtra(SUBSCRIPTION_ID_X2, subscriptionIdX2)
            putExtra(SUBSCRIPTION_ID_X3, subscriptionIdX3)
            putExtra(SHOW_LIFEBUOY, showLifebuoy)
            putExtra(PLAY_STORE_INSTALLED, playStoreInstalled)
            putExtra(SHOW_COLLECTION, showCollection)
            putExtra(RU_STORE, ruStoreInstalled)
            startActivity(this)
        }
    }

    fun startCustomizationActivity(showAccentColor : Boolean = true, isCollection : Boolean = false, licensingKey: String = "",
                                   productIdX1: String = "", productIdX2: String = "", productIdX3: String = "",
                                   subscriptionIdX1: String = "", subscriptionIdX2: String = "", subscriptionIdX3: String = "",
                                   showLifebuoy: Boolean = resources.getBoolean(R.bool.show_lifebuoy),
                                   playStoreInstalled: Boolean = true,
                                   ruStoreInstalled: Boolean = false) {
        if (!packageName.contains("ywdoog".reversed(), true)) {
            if (baseConfig.appRunCount > 100) {
                val label = "You are using a fake version of the app. For your own safety download the original one from play.google.com. Thanks"
                ConfirmationDialog(this, label, positive = R.string.ok, negative = 0) {
                    launchMoreAppsFromUsIntent()
                }
                return
            }
        }

        Intent(applicationContext, CustomizationActivity::class.java).apply {
            putExtra(APP_ICON_IDS, getAppIconIDs())
            putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
            putExtra(SHOW_ACCENT_COLOR, showAccentColor)
            putExtra(IS_COLLECTION, isCollection)
            putExtra(GOOGLE_PLAY_LICENSING_KEY, licensingKey)
            putExtra(PRODUCT_ID_X1, productIdX1)
            putExtra(PRODUCT_ID_X2, productIdX2)
            putExtra(PRODUCT_ID_X3, productIdX3)
            putExtra(SUBSCRIPTION_ID_X1, subscriptionIdX1)
            putExtra(SUBSCRIPTION_ID_X2, subscriptionIdX2)
            putExtra(SUBSCRIPTION_ID_X3, subscriptionIdX3)
            putExtra(SHOW_LIFEBUOY, showLifebuoy)
            putExtra(PLAY_STORE_INSTALLED, playStoreInstalled)
            putExtra(RU_STORE, ruStoreInstalled)
            startActivity(this)
        }
    }

    fun handleCustomizeColorsClick() {
        if (isOrWasThankYouInstalled()) {
            startCustomizationActivity()
        } else {
            FeatureLockedDialog(this) {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun launchCustomizeNotificationsIntent() {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun launchChangeAppLanguageIntent() {
        try {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                startActivity(this)
            }
        } catch (e: Exception) {
            openDeviceSettings()
        }
    }

    // synchronous return value determines only if we are showing the SAF dialog, callback result tells if the SD or OTG permission has been granted
    fun handleSAFDialog(path: String, callback: (success: Boolean) -> Unit): Boolean {
        hideKeyboard()
        return if (!packageName.startsWith("com.goodwy")) {
            callback(true)
            false
        } else if (isShowingSAFDialog(path) || isShowingOTGDialog(path)) {
            funAfterSAFPermission = callback
            true
        } else {
            callback(true)
            false
        }
    }

    fun handleSAFDialogSdk30(path: String, callback: (success: Boolean) -> Unit): Boolean {
        hideKeyboard()
        return if (!packageName.startsWith("com.goodwy")) {
            callback(true)
            false
        } else if (isShowingSAFDialogSdk30(path)) {
            funAfterSdk30Action = callback
            true
        } else {
            callback(true)
            false
        }
    }

    fun checkManageMediaOrHandleSAFDialogSdk30(path: String, callback: (success: Boolean) -> Unit): Boolean {
        hideKeyboard()
        return if (canManageMedia()) {
            callback(true)
            false
        } else {
            handleSAFDialogSdk30(path, callback)
        }
    }

    fun handleSAFCreateDocumentDialogSdk30(path: String, callback: (success: Boolean) -> Unit): Boolean {
        hideKeyboard()
        return if (!packageName.startsWith("com.goodwy")) {
            callback(true)
            false
        } else if (isShowingSAFCreateDocumentDialogSdk30(path)) {
            funAfterSdk30Action = callback
            true
        } else {
            callback(true)
            false
        }
    }

    fun handleAndroidSAFDialog(path: String, callback: (success: Boolean) -> Unit): Boolean {
        hideKeyboard()
        return if (!packageName.startsWith("com.goodwy")) {
            callback(true)
            false
        } else if (isShowingAndroidSAFDialog(path)) {
            funAfterSAFPermission = callback
            true
        } else {
            callback(true)
            false
        }
    }

    fun handleOTGPermission(callback: (success: Boolean) -> Unit) {
        hideKeyboard()
        if (baseConfig.OTGTreeUri.isNotEmpty()) {
            callback(true)
            return
        }

        funAfterSAFPermission = callback
        WritePermissionDialog(this, Mode.Otg) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                try {
                    startActivityForResult(this, OPEN_DOCUMENT_TREE_OTG)
                    return@apply
                } catch (e: Exception) {
                    type = "*/*"
                }

                try {
                    startActivityForResult(this, OPEN_DOCUMENT_TREE_OTG)
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                } catch (e: Exception) {
                    toast(R.string.unknown_error_occurred)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    fun deleteSDK30Uris(uris: List<Uri>, callback: (success: Boolean) -> Unit) {
        hideKeyboard()
        if (isRPlus()) {
            funAfterSdk30Action = callback
            try {
                val deleteRequest = MediaStore.createDeleteRequest(contentResolver, uris).intentSender
                startIntentSenderForResult(deleteRequest, DELETE_FILE_SDK_30_HANDLER, null, 0, 0, 0)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        } else {
            callback(false)
        }
    }

    @SuppressLint("NewApi")
    fun trashSDK30Uris(uris: List<Uri>, toTrash: Boolean, callback: (success: Boolean) -> Unit) {
        hideKeyboard()
        if (isRPlus()) {
            funAfterTrash30File = callback
            try {
                val trashRequest = MediaStore.createTrashRequest(contentResolver, uris, toTrash).intentSender
                startIntentSenderForResult(trashRequest, TRASH_FILE_SDK_30_HANDLER, null, 0, 0, 0)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        } else {
            callback(false)
        }
    }

    @SuppressLint("NewApi")
    fun updateSDK30Uris(uris: List<Uri>, callback: (success: Boolean) -> Unit) {
        hideKeyboard()
        if (isRPlus()) {
            funAfterUpdate30File = callback
            try {
                val writeRequest = MediaStore.createWriteRequest(contentResolver, uris).intentSender
                startIntentSenderForResult(writeRequest, UPDATE_FILE_SDK_30_HANDLER, null, 0, 0, 0)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        } else {
            callback(false)
        }
    }

    @SuppressLint("NewApi")
    fun handleRecoverableSecurityException(callback: (success: Boolean) -> Unit) {
        try {
            callback.invoke(true)
        } catch (securityException: SecurityException) {
            if (isQPlus()) {
                funRecoverableSecurity = callback
                val recoverableSecurityException = securityException as? RecoverableSecurityException ?: throw securityException
                val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                startIntentSenderForResult(intentSender, RECOVERABLE_SECURITY_HANDLER, null, 0, 0, 0)
            } else {
                callback(false)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun launchMediaManagementIntent(callback: () -> Unit) {
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
            data = Uri.parse("package:$packageName")
            try {
                startActivityForResult(this, MANAGE_MEDIA_RC)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
        funAfterManageMediaPermission = callback
    }

    fun copyMoveFilesTo(
        fileDirItems: ArrayList<FileDirItem>, source: String, destination: String, isCopyOperation: Boolean, copyPhotoVideoOnly: Boolean,
        copyHidden: Boolean, callback: (destinationPath: String) -> Unit
    ) {
        if (source == destination) {
            toast(R.string.source_and_destination_same)
            return
        }

        if (!getDoesFilePathExist(destination)) {
            toast(R.string.invalid_destination)
            return
        }

        handleSAFDialog(destination) {
            if (!it) {
                copyMoveListener.copyFailed()
                return@handleSAFDialog
            }

            handleSAFDialogSdk30(destination) {
                if (!it) {
                    copyMoveListener.copyFailed()
                    return@handleSAFDialogSdk30
                }

                copyMoveCallback = callback
                var fileCountToCopy = fileDirItems.size
                if (isCopyOperation) {
                    val recycleBinPath = fileDirItems.first().isRecycleBinPath(this)
                    if (canManageMedia() && !recycleBinPath) {
                        val fileUris = getFileUrisFromFileDirItems(fileDirItems)
                        updateSDK30Uris(fileUris) { sdk30UriSuccess ->
                            if (sdk30UriSuccess) {
                                startCopyMove(fileDirItems, destination, isCopyOperation, copyPhotoVideoOnly, copyHidden)
                            }
                        }
                    } else {
                        startCopyMove(fileDirItems, destination, isCopyOperation, copyPhotoVideoOnly, copyHidden)
                    }
                } else {
                    if (isPathOnOTG(source) || isPathOnOTG(destination) || isPathOnSD(source) || isPathOnSD(destination) ||
                        isRestrictedSAFOnlyRoot(source) || isRestrictedSAFOnlyRoot(destination) ||
                        isAccessibleWithSAFSdk30(source) || isAccessibleWithSAFSdk30(destination) ||
                        fileDirItems.first().isDirectory
                    ) {
                        handleSAFDialog(source) { safSuccess ->
                            if (safSuccess) {
                                val recycleBinPath = fileDirItems.first().isRecycleBinPath(this)
                                if (canManageMedia() && !recycleBinPath) {
                                    val fileUris = getFileUrisFromFileDirItems(fileDirItems)
                                    updateSDK30Uris(fileUris) { sdk30UriSuccess ->
                                        if (sdk30UriSuccess) {
                                            startCopyMove(fileDirItems, destination, isCopyOperation, copyPhotoVideoOnly, copyHidden)
                                        }
                                    }
                                } else {
                                    startCopyMove(fileDirItems, destination, isCopyOperation, copyPhotoVideoOnly, copyHidden)
                                }
                            }
                        }
                    } else {
                        try {
                            checkConflicts(fileDirItems, destination, 0, LinkedHashMap()) {
                                toast(R.string.moving)
                                ensureBackgroundThread {
                                    val updatedPaths = ArrayList<String>(fileDirItems.size)
                                    val destinationFolder = File(destination)
                                    for (oldFileDirItem in fileDirItems) {
                                        var newFile = File(destinationFolder, oldFileDirItem.name)
                                        if (newFile.exists()) {
                                            when {
                                                getConflictResolution(it, newFile.absolutePath) == CONFLICT_SKIP -> fileCountToCopy--
                                                getConflictResolution(it, newFile.absolutePath) == CONFLICT_KEEP_BOTH -> newFile = getAlternativeFile(newFile)
                                                else ->
                                                    // this file is guaranteed to be on the internal storage, so just delete it this way
                                                    newFile.delete()
                                            }
                                        }

                                        if (!newFile.exists() && File(oldFileDirItem.path).renameTo(newFile)) {
                                            if (!baseConfig.keepLastModified) {
                                                newFile.setLastModified(System.currentTimeMillis())
                                            }
                                            updatedPaths.add(newFile.absolutePath)
                                            deleteFromMediaStore(oldFileDirItem.path)
                                        }
                                    }

                                    runOnUiThread {
                                        if (updatedPaths.isEmpty()) {
                                            copyMoveListener.copySucceeded(false, fileCountToCopy == 0, destination, false)
                                        } else {
                                            copyMoveListener.copySucceeded(false, fileCountToCopy <= updatedPaths.size, destination, updatedPaths.size == 1)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    }
                }
            }
        }
    }

    fun getAlternativeFile(file: File): File {
        var fileIndex = 1
        var newFile: File?
        do {
            val newName = String.format("%s(%d).%s", file.nameWithoutExtension, fileIndex, file.extension)
            newFile = File(file.parent, newName)
            fileIndex++
        } while (getDoesFilePathExist(newFile!!.absolutePath))
        return newFile
    }

    private fun startCopyMove(
        files: ArrayList<FileDirItem>,
        destinationPath: String,
        isCopyOperation: Boolean,
        copyPhotoVideoOnly: Boolean,
        copyHidden: Boolean
    ) {
        val availableSpace = destinationPath.getAvailableStorageB()
        val sumToCopy = files.sumByLong { it.getProperSize(applicationContext, copyHidden) }
        if (availableSpace == -1L || sumToCopy < availableSpace) {
            checkConflicts(files, destinationPath, 0, LinkedHashMap()) {
                toast(if (isCopyOperation) R.string.copying else R.string.moving)
                val pair = Pair(files, destinationPath)
                handleNotificationPermission { granted ->
                    if (granted) {
                        CopyMoveTask(this, isCopyOperation, copyPhotoVideoOnly, it, copyMoveListener, copyHidden).execute(pair)
                    } else {
                        PermissionRequiredDialog(this, R.string.allow_notifications_files, { openNotificationSettings() })
                    }
                }
            }
        } else {
            val text = String.format(getString(R.string.no_space), sumToCopy.formatSize(), availableSpace.formatSize())
            toast(text, Toast.LENGTH_LONG)
        }
    }

    fun checkConflicts(
        files: ArrayList<FileDirItem>, destinationPath: String, index: Int, conflictResolutions: LinkedHashMap<String, Int>,
        callback: (resolutions: LinkedHashMap<String, Int>) -> Unit
    ) {
        if (index == files.size) {
            callback(conflictResolutions)
            return
        }

        val file = files[index]
        val newFileDirItem = FileDirItem("$destinationPath/${file.name}", file.name, file.isDirectory)
        ensureBackgroundThread {
            if (getDoesFilePathExist(newFileDirItem.path)) {
                runOnUiThread {
                    FileConflictDialog(this, newFileDirItem, files.size > 1) { resolution, applyForAll ->
                        if (applyForAll) {
                            conflictResolutions.clear()
                            conflictResolutions[""] = resolution
                            checkConflicts(files, destinationPath, files.size, conflictResolutions, callback)
                        } else {
                            conflictResolutions[newFileDirItem.path] = resolution
                            checkConflicts(files, destinationPath, index + 1, conflictResolutions, callback)
                        }
                    }
                }
            } else {
                runOnUiThread {
                    checkConflicts(files, destinationPath, index + 1, conflictResolutions, callback)
                }
            }
        }
    }

    fun handlePermission(permissionId: Int, callback: (granted: Boolean) -> Unit) {
        actionOnPermission = null
        if (hasPermission(permissionId)) {
            callback(true)
        } else {
            isAskingPermissions = true
            actionOnPermission = callback
            ActivityCompat.requestPermissions(this, arrayOf(getPermissionString(permissionId)), GENERIC_PERM_HANDLER)
        }
    }

    fun handlePartialMediaPermissions(permissionIds: Collection<Int>, force: Boolean = false, callback: (granted: Boolean) -> Unit) {
        actionOnPermission = null
        if (isUpsideDownCakePlus()) {
            if (hasPermission(PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED) && !force) {
                callback(true)
            } else {
                isAskingPermissions = true
                actionOnPermission = callback
                ActivityCompat.requestPermissions(this, permissionIds.map { getPermissionString(it) }.toTypedArray(), GENERIC_PERM_HANDLER)
            }
        } else {
            if (hasAllPermissions(permissionIds)) {
                callback(true)
            } else {
                isAskingPermissions = true
                actionOnPermission = callback
                ActivityCompat.requestPermissions(this, permissionIds.map { getPermissionString(it) }.toTypedArray(), GENERIC_PERM_HANDLER)
            }
        }
    }

    fun handleNotificationPermission(callback: (granted: Boolean) -> Unit) {
        if (!isTiramisuPlus()) {
            callback(true)
        } else {
            handlePermission(PERMISSION_POST_NOTIFICATIONS) { granted ->
                callback(granted)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isAskingPermissions = false
        if (requestCode == GENERIC_PERM_HANDLER && grantResults.isNotEmpty()) {
            actionOnPermission?.invoke(grantResults[0] == 0)
        }
    }

    val copyMoveListener = object : CopyMoveListener {
        override fun copySucceeded(copyOnly: Boolean, copiedAll: Boolean, destinationPath: String, wasCopyingOneFileOnly: Boolean) {
            if (copyOnly) {
                toast(
                    if (copiedAll) {
                        if (wasCopyingOneFileOnly) {
                            R.string.copying_success_one
                        } else {
                            R.string.copying_success
                        }
                    } else {
                        R.string.copying_success_partial
                    }
                )
            } else {
                toast(
                    if (copiedAll) {
                        if (wasCopyingOneFileOnly) {
                            R.string.moving_success_one
                        } else {
                            R.string.moving_success
                        }
                    } else {
                        R.string.moving_success_partial
                    }
                )
            }

            copyMoveCallback?.invoke(destinationPath)
            copyMoveCallback = null
        }

        override fun copyFailed() {
            toast(R.string.copy_move_failed)
            copyMoveCallback = null
        }
    }

    fun checkAppOnSDCard() {
        if (!baseConfig.wasAppOnSDShown && isAppInstalledOnSDCard()) {
            baseConfig.wasAppOnSDShown = true
            ConfirmationDialog(this, "", R.string.app_on_sd_card, R.string.ok, 0) {}
        }
    }

    fun exportSettings(configItems: LinkedHashMap<String, Any>) {
        if (isQPlus()) {
            configItemsToExport = configItems
            ExportSettingsDialog(this, getExportSettingsFilename(), true) { path, filename ->
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, filename)
                    addCategory(Intent.CATEGORY_OPENABLE)

                    try {
                        startActivityForResult(this, SELECT_EXPORT_SETTINGS_FILE_INTENT)
                    } catch (e: ActivityNotFoundException) {
                        toast(R.string.system_service_disabled, Toast.LENGTH_LONG)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE) {
                if (it) {
                    ExportSettingsDialog(this, getExportSettingsFilename(), false) { path, filename ->
                        val file = File(path)
                        getFileOutputStream(file.toFileDirItem(this), true) {
                            exportSettingsTo(it, configItems)
                        }
                    }
                }
            }
        }
    }

    private fun exportSettingsTo(outputStream: OutputStream?, configItems: LinkedHashMap<String, Any>) {
        if (outputStream == null) {
            toast(R.string.unknown_error_occurred)
            return
        }

        ensureBackgroundThread {
            outputStream.bufferedWriter().use { out ->
                for ((key, value) in configItems) {
                    out.writeLn("$key=$value")
                }
            }

            toast(R.string.settings_exported_successfully)
        }
    }

    private fun getExportSettingsFilename(): String {
        val appName = baseConfig.appId.removeSuffix(".debug").removeSuffix(".pro").removePrefix("com.goodwy.")
        return "$appName-settings_${getCurrentFormattedDateTime()}"
    }

    @SuppressLint("InlinedApi")
    protected fun launchSetDefaultDialerIntent() {
        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_DIALER)
            }
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName).apply {
                try {
                    startActivityForResult(this, REQUEST_CODE_SET_DEFAULT_DIALER)
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.no_app_found)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun setDefaultCallerIdApp() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_CALLER_ID)
        }
    }
}
