package nl.bromnav

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skydoves.balloon.ArrowOrientation
import com.skydoves.balloon.Balloon
import com.skydoves.balloon.BalloonAnimation
import com.skydoves.powermenu.MenuAnimation
import com.skydoves.powermenu.PowerMenu
import com.skydoves.powermenu.PowerMenuItem

/**
 * BromNav design system: one place for sizes, colours and the components every screen is built
 * from (Material Components + PowerMenu, Balloon, Flexbox from awesome-android-ui).
 * Rules: big touch targets (≥ 48 dp), text ≥ 14 sp, nothing essential in the screen corners,
 * information as wrapping chips instead of long lines (no overlapping, no cut-off text).
 */
object Ui {
    enum class Tone { NEUTRAL, BLUE, ORANGE, RED, GREEN, PURPLE }

    fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun c(hex: String) = Color.parseColor(hex)

    // --- palette (follows Pal.dark)
    val primary get() = c(if (Pal.dark) "#8AB4FF" else "#1F6FE5")
    val onPrimary get() = c(if (Pal.dark) "#0A2A5C" else "#FFFFFF")
    val action get() = c("#1F6FE5")                 // filled buttons: same blue in both themes (white text)
    val success get() = c("#1E9E4A")
    val danger get() = c("#D93025")
    val warning get() = c("#E8900C")

    fun toneBg(t: Tone): Int = when (t) {
        Tone.NEUTRAL -> c(if (Pal.dark) "#2A2F37" else "#E9ECF1")
        Tone.BLUE -> c(if (Pal.dark) "#1F3F73" else "#D8E6FF")
        Tone.ORANGE -> c(if (Pal.dark) "#5C3A00" else "#FFE3BD")
        Tone.RED -> c(if (Pal.dark) "#6B1A16" else "#FFDAD6")
        Tone.GREEN -> c(if (Pal.dark) "#174D2A" else "#D3F2DC")
        Tone.PURPLE -> c(if (Pal.dark) "#4A2F66" else "#EEDCFF")
    }
    fun toneFg(t: Tone): Int = when (t) {
        Tone.NEUTRAL -> c(Pal.text)
        Tone.BLUE -> c(if (Pal.dark) "#D8E6FF" else "#0A2A5C")
        Tone.ORANGE -> c(if (Pal.dark) "#FFE3BD" else "#4A2A00")
        Tone.RED -> c(if (Pal.dark) "#FFDAD6" else "#5F1410")
        Tone.GREEN -> c(if (Pal.dark) "#D3F2DC" else "#0D3B1C")
        Tone.PURPLE -> c(if (Pal.dark) "#EEDCFF" else "#3B1A5C")
    }

    /** Elevated rounded card (map overlays, list rows, settings rows). */
    fun card(ctx: Context, radius: Int = 24, elevation: Int = 6, color: Int = Pal.card) = MaterialCardView(ctx).apply {
        this.radius = dp(ctx, radius).toFloat(); cardElevation = dp(ctx, elevation).toFloat()
        setCardBackgroundColor(color); strokeWidth = 0; useCompatPadding = false
    }

    fun text(ctx: Context, size: Float, color: Int = c(Pal.text), bold: Boolean = false, center: Boolean = false) = TextView(ctx).apply {
        textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER
        includeFontPadding = true
    }

    /** Information chip (not clickable unless [onClick] is given). */
    fun chip(ctx: Context, label: String, tone: Tone = Tone.NEUTRAL, big: Boolean = false, onClick: (() -> Unit)? = null) = Chip(ctx).apply {
        text = label; textSize = if (big) 16f else 14f
        chipBackgroundColor = ColorStateList.valueOf(toneBg(tone)); setTextColor(toneFg(tone))
        chipStrokeWidth = 0f; chipMinHeight = dp(ctx, if (big) 38 else 32).toFloat()
        setEnsureMinTouchTargetSize(onClick != null)
        isClickable = onClick != null; isCheckable = false
        if (onClick != null) setOnClickListener { onClick() }
        chipStartPadding = dp(ctx, 4).toFloat(); chipEndPadding = dp(ctx, 4).toFloat()
    }

    /** Row of chips that wraps onto more lines instead of overlapping or cutting text. */
    fun flex(ctx: Context, gap: Int = 6, center: Boolean = true) = FlexboxLayout(ctx).apply {
        flexWrap = FlexWrap.WRAP; alignItems = AlignItems.CENTER
        justifyContent = if (center) JustifyContent.CENTER else JustifyContent.FLEX_START
        val space = GradientDrawable().apply { setSize(dp(ctx, gap), dp(ctx, gap)) }
        setDividerDrawable(space); setShowDivider(FlexboxLayout.SHOW_DIVIDER_MIDDLE)
    }

    /** Round icon button (emoji icon), tonal surface colour. */
    fun iconButton(ctx: Context, icon: String, size: Int = 54, onClick: (View) -> Unit) = MaterialButton(ctx).apply {
        text = icon; textSize = 22f; insetTop = 0; insetBottom = 0; minWidth = 0; minHeight = 0
        setPadding(0, 0, 0, 0); iconPadding = 0
        cornerRadius = dp(ctx, size / 2); backgroundTintList = ColorStateList.valueOf(Pal.chip); setTextColor(c(Pal.text))
        elevation = dp(ctx, 4).toFloat()
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(ctx, size), dp(ctx, size)).apply { setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0) }
        setOnClickListener { onClick(it) }
    }

    /** Big filled pill button (main actions). */
    fun pill(ctx: Context, label: String, color: Int = action, textColor: Int = Color.WHITE, height: Int = 58, onClick: () -> Unit) = MaterialButton(ctx).apply {
        text = label; isAllCaps = false; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textColor)
        insetTop = 0; insetBottom = 0; cornerRadius = dp(ctx, height / 2); backgroundTintList = ColorStateList.valueOf(color)
        elevation = dp(ctx, 4).toFloat(); minHeight = dp(ctx, height)
        setOnClickListener { onClick() }
    }

    /** Outlined / tonal secondary button. */
    fun tonal(ctx: Context, label: String, tone: Tone = Tone.NEUTRAL, onClick: () -> Unit) = MaterialButton(ctx).apply {
        text = label; isAllCaps = false; textSize = 16f; insetTop = 0; insetBottom = 0; minHeight = dp(ctx, 50)
        cornerRadius = dp(ctx, 25); backgroundTintList = ColorStateList.valueOf(toneBg(tone)); setTextColor(toneFg(tone))
        setOnClickListener { onClick() }
    }

    fun dialog(ctx: Context) = MaterialAlertDialogBuilder(ctx)

    /** Big-item popup menu (PowerMenu). Items: label to action. */
    fun menu(ctx: Context, owner: LifecycleOwner, items: List<Pair<String, () -> Unit>>): PowerMenu {
        var pm: PowerMenu? = null
        val b = PowerMenu.Builder(ctx)
            .addItemList(items.map { PowerMenuItem(it.first, false) })
            .setAnimation(MenuAnimation.SHOWUP_BOTTOM_RIGHT)
            .setMenuRadius(dp(ctx, 20).toFloat()).setMenuShadow(dp(ctx, 10).toFloat())
            .setTextColor(c(Pal.text)).setTextSize(18).setTextGravity(Gravity.START)
            .setMenuColor(Pal.card).setSelectedMenuColor(toneBg(Tone.BLUE)).setSelectedTextColor(toneFg(Tone.BLUE))
            .setAutoDismiss(true).setLifecycleOwner(owner)
            .setWidth(dp(ctx, 300))
            .setOnMenuItemClickListener { pos, _ -> pm?.dismiss(); items[pos].second() }
        pm = b.build()
        return pm
    }

    /** Short tooltip next to a view (Balloon), disappears after [ms]. */
    fun hint(ctx: Context, owner: LifecycleOwner, msg: String, ms: Long = 4000, arrow: ArrowOrientation = ArrowOrientation.BOTTOM) =
        Balloon.Builder(ctx)
            .setText(msg).setTextSize(15f).setTextColor(toneFg(Tone.BLUE)).setBackgroundColor(toneBg(Tone.BLUE))
            .setPaddingHorizontal(14).setPaddingVertical(10).setCornerRadius(14f).setArrowSize(12)
            .setArrowOrientation(arrow).setArrowPosition(0.5f).setWidthRatio(0.75f)
            .setBalloonAnimation(BalloonAnimation.OVERSHOOT).setAutoDismissDuration(ms).setDismissWhenTouchOutside(true)
            .setLifecycleOwner(owner).build()
}
