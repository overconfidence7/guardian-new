package app.guardianmode.core

/**
 * Which installed apps count as "money moves" or "remote control" for the tripwires.
 *
 * The built-in list is a seed of widely used US apps; families extend it per phone
 * from the dashboard (e.g. a local credit union) through [PauseSettings.extraPackages].
 * Matching is by exact package name, never by scanning app contents.
 */
class AppCatalog(
    private val builtIn: Map<String, AppCategory> = BUILT_IN,
) {

    fun categoryOf(packageName: String, settings: ProtectionSettings): AppCategory? {
        settings.pauseTripwires.extraPackages[packageName]?.let { return it }
        return builtIn[packageName]
    }

    fun isRemoteAccess(packageName: String, settings: ProtectionSettings): Boolean =
        categoryOf(packageName, settings) == AppCategory.REMOTE_ACCESS &&
            packageName !in settings.remoteAccessAlarm.trustedPackages

    fun packagesIn(category: AppCategory): Set<String> =
        builtIn.filterValues { it == category }.keys

    companion object {
        private fun cat(category: AppCategory, vararg packages: String) = packages.associateWith { category }

        val BUILT_IN: Map<String, AppCategory> = buildMap {
            putAll(
                cat(
                    AppCategory.REMOTE_ACCESS,
                    "com.anydesk.anydeskandroid",
                    "com.teamviewer.quicksupport.market",
                    "com.teamviewer.teamviewer.market.mobile",
                    "com.teamviewer.host.market",
                    "com.carriez.flutter_hbb", // RustDesk
                    "com.splashtop.sos",
                    "com.splashtop.remote.pad.v2",
                    "com.splashtop.streamer.csrs",
                    "com.zoho.assist.agent",
                    "com.zoho.assist",
                    "com.screenconnect.androidclient",
                    "com.logmein.rescuemobile",
                    "com.sand.airdroid",
                    "com.sand.airmirror",
                    "com.realvnc.viewer.android",
                    "com.google.chromeremotedesktop",
                    "com.microsoft.rdc.androidx",
                ),
            )
            putAll(
                cat(
                    AppCategory.PAYMENT,
                    "com.paypal.android.p2pmobile",
                    "com.venmo",
                    "com.squareup.cash",
                    "com.zellepay.zelle",
                    "com.westernunion.android.mtapp",
                    "com.moneygram.moneygram",
                    "com.remitly.androidapp",
                    "com.xoom.android.app",
                    "com.worldremit.android",
                    "com.google.android.apps.walletnfcrel",
                    "com.samsung.android.spay",
                ),
            )
            putAll(
                cat(
                    AppCategory.GIFT_CARD,
                    "com.raise.raise",
                    "com.cardcash.app",
                    "com.amazon.mShop.android.shopping",
                    "com.walmart.android",
                    "com.target.ui",
                    "com.bestbuy.android",
                    "com.cvs.launchers.cvs",
                    "com.walgreens.android",
                    "com.ebay.mobile",
                ),
            )
            putAll(
                cat(
                    AppCategory.CRYPTO,
                    "com.coinbase.android",
                    "com.coinbase.wallet",
                    "co.mona.android", // Crypto.com
                    "com.binance.dev",
                    "com.binance.us",
                    "com.kraken.trade",
                    "com.robinhood.android",
                    "com.wallet.crypto.trustapp",
                    "io.metamask",
                    "exodusmovement.exodus",
                    "com.coinme.coinme",
                    "com.bitpay.wallet",
                ),
            )
            putAll(
                cat(
                    AppCategory.BANK,
                    "com.chase.sig.android",
                    "com.infonow.bofa",
                    "com.wf.wellsfargomobile",
                    "com.citi.citimobile",
                    "com.konylabs.capitalone",
                    "com.usbank.mobilebanking",
                    "com.pnc.ecommerce.mobile",
                    "com.tdbank",
                    "com.truist.mobile",
                    "com.navyfederal.android",
                    "com.usaa.mobile.android.usaa",
                    "com.discoverfinancial.mobile",
                    "com.ally.MobileBanking",
                    "com.schwab.mobile",
                    "com.fidelity.android",
                    "com.americanexpress.android.acctsvcs.us",
                ),
            )
        }
    }
}
