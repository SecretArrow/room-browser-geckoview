package com.roombrowser.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roombrowser.browser.wallet.DappDecision
import com.roombrowser.browser.wallet.DappPermissionRecord
import com.roombrowser.browser.wallet.DappRequest
import com.roombrowser.browser.wallet.NetworkRecord
import com.roombrowser.browser.wallet.WalletEngineApi
import com.roombrowser.domain.wallet.model.AmountFormat
import com.roombrowser.domain.wallet.model.BroadcastResult
import com.roombrowser.domain.wallet.model.ChainType
import com.roombrowser.domain.wallet.model.FeeEstimate
import com.roombrowser.domain.wallet.model.NetworkConfig
import com.roombrowser.qr.QrCodeGenerator
import com.roombrowser.ui.common.EmptyState
import com.roombrowser.ui.common.LocalRoomExtras
import com.roombrowser.ui.common.RoomBottomSheetShape
import com.roombrowser.ui.common.RoomCardShape
import com.roombrowser.ui.common.RoomSheetHeader
import com.roombrowser.ui.common.SettingActionRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Wallet sheets: the dApp confirmation sheets (also hosted by BrowserScreen —
 * every one of them is PUBLIC and self-contained: hand it the pending
 * [DappRequest] + the engine and it renders, decides and settles on its own)
 * plus the dashboard's own send / receive / account / network sheets.
 *
 * Sheet standard (Task 4-a tokens): [RoomBottomSheetShape] (10dp top
 * corners), the single centered M3 drag handle, 16dp horizontal gutters,
 * titleLarge header, 24dp tail spacer. M3's sheet dialog already lifts
 * content above the IME and pads above the nav bar (safeDrawing bottom) —
 * no sheet adds its own imePadding.
 *
 * SECURITY: no sheet ever renders key material. The only secret a sheet can
 * receive is a private key being TYPED by its owner (masked field). dApp
 * sheets show the WebView-verified host, never a page-claimed origin.
 */

// ---------------------------------------------------------------------------
// Shared sheet pieces
// ---------------------------------------------------------------------------

/** Quiet explanatory note — mirrors the settings screens' InfoNote look. */
@Composable
internal fun WalletInfoNote(text: String) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape((extras.radius * 0.6f).dp))
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = extras.textSecondary
        )
    }
}

/**
 * Verified-host badge. Every dApp sheet names the host the BRIDGE verified
 * against the WebView (never the page's claimed origin), so the user always
 * signs for the site they think they are on.
 */
@Composable
private fun HostBadge(host: String) {
    val extras = LocalRoomExtras.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Verified,
            contentDescription = null,
            tint = extras.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "Connected site",
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary
            )
            Text(
                host,
                style = MaterialTheme.typography.titleMedium,
                color = extras.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One labeled key/value line inside a confirmation sheet. [muted] drops the
 * value to the secondary colour for facts that are pending or unavailable —
 * the value still reads as a value, without competing with the facts that are
 * actually known.
 */
@Composable
private fun SheetDataRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    muted: Boolean = false
) {
    val extras = LocalRoomExtras.current
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
        Text(
            value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = if (muted) extras.textSecondary else extras.textPrimary
        )
    }
}

/**
 * The facts a confirmation is weighed on, collected into ONE bounded panel.
 * The same lines loose between a header and two buttons read as background;
 * grouped under a shared surface they read as the thing being decided.
 */
@Composable
private fun SheetDetailGroup(content: @Composable ColumnScope.() -> Unit) {
    val extras = LocalRoomExtras.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoomCardShape)
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content
    )
}

/**
 * The amount a confirmation is about, as the sheet's headline. The one fact
 * an approval turns on should not be a 13sp line among six others.
 */
@Composable
private fun SheetAmountHero(amount: String, caption: String, muted: Boolean = false) {
    val extras = LocalRoomExtras.current
    Column {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = extras.textSecondary
        )
        Text(
            amount,
            style = MaterialTheme.typography.headlineSmall,
            color = if (muted) extras.textSecondary else extras.textPrimary
        )
    }
}

/**
 * Icon-only action with a Material 3 tooltip. The tooltip is what makes a bare
 * glyph legible — on hover with a pointer, on long-press by touch — and it
 * doubles as the accessible name, because an icon button that shows only a
 * picture is a mystery to a screen reader. The 48dp touch target is
 * IconButton's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = LocalRoomExtras.current.icon
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label }
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
    }
}

/** Scrollable monospace block for messages / raw JSON (bounded height). */
@Composable
private fun MonospaceBlock(text: String, maxHeight: Dp = 180.dp) {
    val extras = LocalRoomExtras.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(extras.surfaceAlt.copy(alpha = 0.6f))
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = extras.textPrimary
        )
    }
}

/** Approve (primary) / Reject (outlined) — the dApp decision row, ≥48dp targets. */
@Composable
private fun ApproveRejectButtons(onApprove: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onApprove,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) { Text("Approve") }
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) { Text("Reject") }
    }
}

/**
 * Sheet state for a sheet whose whole purpose is a DECISION — the ones that
 * end in [ApproveRejectButtons]: open at full height from the first frame.
 *
 * WHY skipPartiallyExpanded: at the default partially-expanded height the
 * answer pair sits below the fold on a small screen. The CI emulator is
 * 320x640, where the Connect sheet's content (header, host, chain + origin,
 * account, the "what approving means" note, then the buttons) is taller than
 * the half-height sheet — so the user gets a request they can read and no
 * control they can reach, while the natural "get this out of the way" gesture,
 * a tap outside, settles it as a REJECTION. It also made the sheet's landing
 * state depend on when its content was measured, so the same request rendered
 * differently run to run. Opening expanded puts the decision on screen; the
 * content still scrolls when it outgrows the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun confirmSheetState(): SheetState =
    rememberModalBottomSheetState(skipPartiallyExpanded = true)

/** "0x1234…abcd"-style shortening; short values pass through untouched. */
internal fun shortenAddress(address: String): String =
    if (address.length <= 12) address else address.take(6) + "…" + address.takeLast(4)

/**
 * Best-effort transaction URL for a network's block explorer (used by the
 * send-success snackbar and activity rows when no full URL was pre-computed).
 * Path convention per chain family; null when the network has no explorer.
 *
 * The paths follow the explorer each family's preset actually names, which is
 * why SUI is "tx" and not "txblock": "txblock" belongs to suiexplorer.com
 * (explorer.sui.io 307-redirects there), while the preset points at
 * suiscan.xyz, whose own bundle builds every transaction link as
 * `/tx/${digest}` — "txblock" appears nowhere in it, so the old path landed on
 * suiscan's not-found view. Checked against suiscan's shipped main.js on
 * 2026-10-03.
 */
internal fun explorerTxUrl(config: NetworkConfig?, hash: String): String? {
    if (config == null) return null
    val base = config.explorerUrl?.trimEnd('/') ?: return null
    val path = when (config.chainType) {
        ChainType.EVM, ChainType.SOLANA, ChainType.BITCOIN, ChainType.OCTRA -> "tx"
        ChainType.APTOS -> "txn"
        ChainType.SUI -> "tx"
        ChainType.COSMOS -> "txs"
        ChainType.TRON -> "#/transaction"
    }
    return "$base/$path/$hash"
}

/** EVM-style address (0x + 40 hex). */
private val evmAddress = Regex("^0x[0-9a-fA-F]{40}$")

/** Base58 (Solana-style) address / key encoding. */
private val base58String = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

/** Plain decimal amount with at most 18 fraction digits. */
private val decimalAmount = Regex("^\\d{1,18}(\\.\\d{1,18})?$")

/** URL shape accepted for RPC / explorer inputs. */
private val httpUrl = Regex("^https?://\\S+$")

/** UI-level recipient sanity check per chain family (the engine is authoritative). */
private fun isLikelyAddress(chain: ChainType, value: String): Boolean = when (chain) {
    ChainType.EVM -> evmAddress.matches(value)
    ChainType.SOLANA -> base58String.matches(value)
    ChainType.TRON -> value.length == 34 && value.startsWith("T")
    ChainType.OCTRA -> value.length == 47 && value.startsWith("oct")
    else -> value.length >= 10
}

/** Inline per-chain hint for the recipient field. */
private fun addressHint(chain: ChainType): String = when (chain) {
    ChainType.EVM -> "EVM address: 0x followed by 40 hex characters"
    ChainType.SOLANA -> "Solana address: 32–44 base58 characters"
    ChainType.TRON -> "TRON address: starts with T, 34 base58 characters"
    ChainType.OCTRA -> "Octra address: starts with oct, 47 characters"
    else -> "Check the ${chain.displayName} address format"
}

/** UI-level amount check: plain number, no exponents, ≤18 decimals. */
private fun isDecimalAmount(value: String): Boolean = decimalAmount.matches(value)

/**
 * Humanizes a chain-native token amount: hex ("0xde0b6b…") or decimal
 * base-units are divided by 10^[decimals] and passed through the shared
 * [AmountFormat] (≤7 decimals, truncated); anything unparseable is returned
 * as-is (the raw value is always safe to show).
 */
internal fun formatTxValue(raw: String, decimals: Int): String {
    val clean = raw.trim()
    val asBigInteger = when {
        clean.startsWith("0x") || clean.startsWith("0X") ->
            clean.drop(2).toBigIntegerOrNull(16)
        else -> clean.toBigIntegerOrNull()
    } ?: return clean
    return AmountFormat.fromBaseUnits(asBigInteger, decimals)
}

// ---------------------------------------------------------------------------
// dApp confirmation sheets (PUBLIC: BrowserScreen hosts them over the page)
// ---------------------------------------------------------------------------

/**
 * eth_requestAccounts & co. The account picker appears when the profile has
 * more than one account on the requested chain; Approve exposes the selected
 * account, Reject (and any dismissal) settles with USER_REJECTED.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectRequestSheet(
    request: DappRequest.Connect,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val accounts by engine.accounts.collectAsState()
    val chainAccounts = accounts.filter { it.chainType == request.chainType }
    var chosenId by remember(request.id) { mutableStateOf<String?>(null) }
    val selected = chainAccounts.firstOrNull { it.id == chosenId } ?: chainAccounts.firstOrNull()

    fun settle(approved: Boolean) {
        engine.decideDappRequest(
            DappDecision(
                requestId = request.id,
                approved = approved,
                chosenAccountId = if (approved) selected?.id else null
            )
        )
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        shape = RoomBottomSheetShape,
        sheetState = confirmSheetState()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Connect site")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDetailGroup {
                SheetDataRow("Chain", request.chainType.displayName)
                SheetDataRow("Origin", request.originUrl)
            }
            Spacer(Modifier.height(10.dp))
            if (chainAccounts.size > 1) {
                Text(
                    "Account to share",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalRoomExtras.current.textSecondary
                )
                chainAccounts.forEach { account ->
                    RadioRow(
                        label = "${account.label} · ${shortenAddress(account.address)}",
                        selected = account.id == selected?.id,
                        onSelect = { chosenId = account.id }
                    )
                }
                Spacer(Modifier.height(10.dp))
            } else {
                selected?.let {
                    SheetDataRow("Account", "${it.label} · ${shortenAddress(it.address)}")
                }
            }
            WalletInfoNote(
                "Approving lets this site see the selected address and ask for " +
                    "transactions and signatures — every request still asks first."
            )
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** personal_sign & co. The message body is scrollable monospace, never truncated silently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignMessageSheet(
    request: DappRequest.SignMessage,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        shape = RoomBottomSheetShape,
        sheetState = confirmSheetState()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Sign message")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Account", shortenAddress(request.accountAddress))
            Spacer(Modifier.height(10.dp))
            MonospaceBlock(request.displayMessage)
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * eth_signTypedData_v4 (EIP-712): domain + primaryType summary with the raw
 * JSON behind a "Show raw JSON" toggle for the full payload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignTypedDataSheet(
    request: DappRequest.SignTypedData,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    var rawShown by remember(request.id) { mutableStateOf(false) }
    val parsed = remember(request.typedDataJson) {
        runCatching { JSONObject(request.typedDataJson) }.getOrNull()
    }
    val domain = parsed?.optJSONObject("domain")
    val primaryType = parsed?.optString("primaryType")?.ifBlank { null }

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        shape = RoomBottomSheetShape,
        sheetState = confirmSheetState()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Sign typed data")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDataRow("Account", shortenAddress(request.accountAddress))
            domain?.let {
                val parts = listOfNotNull(
                    it.optString("name").ifBlank { null },
                    it.optString("version").ifBlank { null }
                )
                if (parts.isNotEmpty()) SheetDataRow("Domain", parts.joinToString(" · "))
            }
            if (primaryType != null) SheetDataRow("Primary type", primaryType)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { rawShown = !rawShown }) {
                Text(if (rawShown) "Hide raw JSON" else "Show raw JSON")
            }
            if (rawShown) MonospaceBlock(request.typedDataJson, maxHeight = 240.dp)
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * eth_sendTransaction & co. Value is humanized from the chain's base units
 * when parseable; the calldata is truncated with an expand toggle; the fee
 * estimate comes pre-computed on the request (null = unknown/offline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTransactionSheet(
    request: DappRequest.SendTransaction,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val networks by engine.networks.collectAsState()
    val network = networks.firstOrNull { it.config.id == request.networkId }?.config
    val decimals = network?.nativeDecimals ?: 18
    var dataExpanded by remember(request.id) { mutableStateOf(false) }
    val params = remember(request.txParamsJson) {
        runCatching { JSONObject(request.txParamsJson) }.getOrNull()
    }
    val to = params?.optString("to")?.ifBlank { null }
    val value = params?.optString("value")?.ifBlank { null }
    val data = params?.optString("data")?.ifBlank { null }

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        shape = RoomBottomSheetShape,
        sheetState = confirmSheetState()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Send transaction")
            HostBadge(request.host)
            Spacer(Modifier.height(12.dp))
            // The amount leads, at headline size: it is the fact the approval
            // turns on, and it used to be one 13sp line among six.
            val formatted = value?.let { formatTxValue(it, decimals) }
            val symbol = network?.nativeSymbol
            SheetAmountHero(
                amount = when {
                    formatted == null -> "No native amount"
                    symbol == null -> formatted
                    else -> "$formatted $symbol"
                },
                caption = if (formatted == null) "Contract call" else "Amount",
                muted = formatted == null
            )
            Spacer(Modifier.height(12.dp))
            SheetDetailGroup {
                SheetDataRow("Network", network?.name ?: request.networkId)
                SheetDataRow("From", shortenAddress(request.accountAddress))
                if (to != null) {
                    // Full, never shortened: a confirmation is the one place
                    // the whole recipient must be readable — a shortened
                    // address is exactly what address-poisoning relies on.
                    SheetDataRow("Recipient", to, monospace = true)
                }
                SheetDataRow(
                    "Fee estimate",
                    request.feeEstimate?.let { "${it.estimatedCost} (${it.label})" }
                        ?: "Unavailable — the network did not answer"
                )
            }
            if (data != null) {
                Spacer(Modifier.height(10.dp))
                SheetDataRow(
                    "Data",
                    if (dataExpanded || data.length <= 66) data else data.take(66) + "…"
                )
                if (data.length > 66) {
                    TextButton(onClick = { dataExpanded = !dataExpanded }) {
                        Text(if (dataExpanded) "Hide data" else "Show all data")
                    }
                }
                // The amount above is the chain's OWN coin; anything the
                // calldata moves (tokens, approvals) is not on this sheet.
                WalletInfoNote(
                    "This request carries contract data, so it can move assets this " +
                        "sheet does not show — tokens, for example. Only approve it if " +
                        "you trust the site you are on."
                )
            }
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** wallet_switchEthereumChain: current network → requested network summary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitchChainSheet(
    request: DappRequest.SwitchChain,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val networks by engine.networks.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val current = activeNetworks[request.chainType]
    val target = networks.firstOrNull { it.config.id == request.targetNetworkId }?.config

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        shape = RoomBottomSheetShape,
        sheetState = confirmSheetState()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Switch network")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDetailGroup {
                SheetDataRow("Current", current?.name ?: "None selected")
                SheetDataRow("Requested", target?.name ?: request.targetNetworkId)
                target?.let {
                    SheetDataRow("Chain ID", it.chainId)
                }
            }
            target?.let {
                if (it.isTestnet) {
                    WalletInfoNote("This network is marked as a testnet.")
                }
            }
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * wallet_addEthereumChain: the network the site proposes, as validated by
 * the engine. Approve lets the engine persist it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChainSheet(
    request: DappRequest.AddChain,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    val proposed = request.proposed

    fun settle(approved: Boolean) {
        engine.decideDappRequest(DappDecision(requestId = request.id, approved = approved))
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { settle(false) },
        shape = RoomBottomSheetShape,
        sheetState = confirmSheetState()
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Add network")
            HostBadge(request.host)
            Spacer(Modifier.height(8.dp))
            SheetDetailGroup {
                SheetDataRow("Name", proposed.name)
                SheetDataRow("Chain ID", proposed.chainId)
                SheetDataRow("Symbol", proposed.nativeSymbol)
                proposed.rpcUrls.firstOrNull()?.let { SheetDataRow("RPC URL", it) }
                proposed.explorerUrl?.let { SheetDataRow("Explorer", it) }
            }
            if (proposed.isTestnet) {
                WalletInfoNote("This network is marked as a testnet.")
            }
            Spacer(Modifier.height(16.dp))
            ApproveRejectButtons(
                onApprove = { settle(true) },
                onReject = { settle(false) }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Dispatcher for the engine's pending-request queue: renders the right
 * confirmation sheet for ANY [DappRequest] without the host needing to know
 * the variant. Approve/Reject settle through the engine; dismissing the
 * sheet (back / outside tap) is a rejection (EIP-1193 UX).
 */
@Composable
fun WalletDappRequestSheet(
    request: DappRequest,
    engine: WalletEngineApi,
    onDismiss: () -> Unit
) {
    when (request) {
        is DappRequest.Connect -> ConnectRequestSheet(request, engine, onDismiss)
        is DappRequest.SignMessage -> SignMessageSheet(request, engine, onDismiss)
        is DappRequest.SignTypedData -> SignTypedDataSheet(request, engine, onDismiss)
        is DappRequest.SendTransaction -> SendTransactionSheet(request, engine, onDismiss)
        is DappRequest.SwitchChain -> SwitchChainSheet(request, engine, onDismiss)
        is DappRequest.AddChain -> AddChainSheet(request, engine, onDismiss)
    }
}

// ---------------------------------------------------------------------------
// Dashboard sheets
// ---------------------------------------------------------------------------

/**
 * Native send: account picker (when the chain has more than one), recipient
 * + amount with per-chain validation, a live (debounced) fee estimate, and a
 * Confirm that calls [WalletEngineApi.sendNative]. Errors stay INLINE in the
 * sheet so the typed inputs survive; success hands the hash + explorer URL
 * to [onSent] and closes.
 *
 * [initialAccountId] is the account the dashboard shows as active for this
 * chain: the sheet opens on the same account the user was just looking at,
 * instead of silently falling back to whichever account happens to be first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSheet(
    engine: WalletEngineApi,
    chainType: ChainType,
    initialAccountId: String? = null,
    onDismiss: () -> Unit,
    onSent: (hash: String, explorerUrl: String?) -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    val accounts by engine.accounts.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val chainAccounts = accounts.filter { it.chainType == chainType }
    var chosenId by remember { mutableStateOf(initialAccountId) }
    val selected = chainAccounts.firstOrNull { it.id == chosenId } ?: chainAccounts.firstOrNull()
    val network = activeNetworks[chainType]
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var fee by remember { mutableStateOf<FeeEstimate?>(null) }
    var feeLoading by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)

    val toBlank = to.trim().isEmpty()
    val toInvalid = !toBlank && !isLikelyAddress(chainType, to.trim())
    val amountBlank = amount.isBlank()
    val amountInvalid = !amountBlank && !isDecimalAmount(amount.trim())
    val canSubmit = selected != null && network != null && !toBlank && !toInvalid &&
        !amountBlank && !amountInvalid && !sending

    // Fee estimate — debounced restart on every input change; an absent result
    // says so in the fee row ("the network did not answer") rather than
    // leaving a blank the user would read as free.
    LaunchedEffect(selected?.id, network?.id, to, amount) {
        if (selected == null || network == null || toBlank || amountBlank || amountInvalid) {
            fee = null
        } else {
            delay(300)
            feeLoading = true
            fee = runCatching {
                engine.estimateSendFee(chainType, network.id, selected.id, to.trim(), amount.trim())
            }.getOrNull()
            feeLoading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Send ${chainType.displayName}")
            if (network == null) {
                WalletInfoNote(
                    "No active ${chainType.displayName} network — pick one under Networks first."
                )
            }
            if (chainAccounts.isEmpty()) {
                WalletInfoNote(
                    "No ${chainType.displayName} account yet — add one under Add account first."
                )
            }
            if (chainAccounts.size > 1) {
                Text(
                    "From account",
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.textSecondary
                )
                chainAccounts.forEach { account ->
                    RadioRow(
                        label = "${account.label} · ${shortenAddress(account.address)}",
                        selected = account.id == selected?.id,
                        onSelect = { chosenId = account.id }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("To address") },
                singleLine = true,
                isError = attempted && (toBlank || toInvalid),
                supportingText = {
                    when {
                        attempted && toBlank -> Text("Recipient address is required")
                        toInvalid -> Text(addressHint(chainType))
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount (${network?.nativeSymbol ?: chainType.displayName})") },
                singleLine = true,
                isError = attempted && (amountBlank || amountInvalid),
                supportingText = {
                    when {
                        attempted && amountBlank -> Text("Amount is required")
                        amountInvalid -> Text("Enter a plain number, e.g. 0.01")
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            // Network, sender and fee sit together as one "what this
            // transaction is and what it costs" block — the fee used to be a
            // bare label/value pair that a user could scroll straight past.
            SheetDetailGroup {
                SheetDataRow(
                    label = "Network",
                    value = network?.name ?: "No network selected"
                )
                SheetDataRow(
                    label = "From",
                    value = selected?.let { "${it.label} · ${shortenAddress(it.address)}" }
                        ?: "No account selected"
                )
                val currentFee = fee
                val feeText = when {
                    chainAccounts.isEmpty() || network == null -> "Unavailable"
                    toBlank || amountBlank || amountInvalid -> "Enter an amount to estimate"
                    feeLoading -> "Estimating…"
                    currentFee != null -> "${currentFee.estimatedCost} (${currentFee.label})"
                    else -> "Unavailable — the network did not answer"
                }
                // "Unavailable" here is an honest read, not an empty slot:
                // the estimate could not be fetched, so the user is told that
                // rather than shown a dash that reads as zero.
                SheetDataRow(
                    label = "Fee estimate",
                    value = feeText,
                    muted = feeText.startsWith("Unavailable") || feeLoading
                )
            }
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        attempted = true
                        val account = selected
                        val net = network
                        if (account == null || net == null) {
                            failure = null
                        } else {
                            sending = true
                            failure = null
                            scope.launch {
                                runCatching {
                                    engine.sendNative(
                                        accountId = account.id,
                                        networkId = net.id,
                                        to = to.trim(),
                                        amount = amount.trim()
                                    )
                                }.onSuccess { result ->
                                    sending = false
                                    when (result) {
                                        is BroadcastResult.Ok -> {
                                            onSent(result.hash, explorerTxUrl(net, result.hash))
                                            onDismiss()
                                        }
                                        is BroadcastResult.Error -> failure = result.message
                                    }
                                }.onFailure { e ->
                                    sending = false
                                    failure = e.message ?: "Send failed"
                                }
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text(if (sending) "Sending…" else "Confirm") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Receive: the account picker (when the chain has more than one), the full
 * address, its QR code and a copy button. Addresses are PUBLIC — the copy
 * is a plain clip (no sensitive flag), unlike password copies.
 *
 * [initialAccountId] preselects the account the dashboard shows as active for
 * this chain, so the address on screen is the one the user was just looking
 * at. The copy button confirms IN PLACE (icon and label both flip for a
 * moment) because a snackbar at the screen edge is far from the finger that
 * asked for the copy and easy to miss entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveSheet(
    engine: WalletEngineApi,
    chainType: ChainType,
    initialAccountId: String? = null,
    onCopyAddress: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accounts by engine.accounts.collectAsState()
    val chainAccounts = accounts.filter { it.chainType == chainType }
    var chosenId by remember { mutableStateOf(initialAccountId) }
    val selected = chainAccounts.firstOrNull { it.id == chosenId } ?: chainAccounts.firstOrNull()
    val address = selected?.address
    var copied by remember(address) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RoomSheetHeader("Receive ${chainType.displayName}")
            if (chainAccounts.size > 1) {
                chainAccounts.forEach { account ->
                    RadioRow(
                        label = "${account.label} · ${shortenAddress(account.address)}",
                        selected = account.id == selected?.id,
                        onSelect = { chosenId = account.id }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (address == null) {
                WalletInfoNote(
                    "No ${chainType.displayName} account yet — add one under Add account first."
                )
            } else {
                val qrBitmap = remember(address) { QrCodeGenerator.generate(address, 512) }
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply { setImageBitmap(qrBitmap) }
                    },
                    update = { it.setImageBitmap(qrBitmap) },
                    modifier = Modifier.size(220.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = LocalRoomExtras.current.textPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onCopyAddress(address)
                        copied = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "Address copied" else "Copy address")
                }
                Spacer(Modifier.height(6.dp))
                WalletInfoNote(
                    "Your address is public — share it to receive ${chainType.displayName} " +
                        "funds. Never share your recovery phrase or private keys."
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Per-chain network selector: radio rows over the chain's ENABLED networks
 * (tap = make active), plus entries into the custom-network form and the
 * Chainlist browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPickerSheet(
    engine: WalletEngineApi,
    chainType: ChainType,
    onAddNetwork: () -> Unit,
    onBrowseChainlist: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val networks by engine.networks.collectAsState()
    val activeNetworks by engine.activeNetworks.collectAsState()
    val active = activeNetworks[chainType]
    val enabledNetworks = networks.filter { it.config.chainType == chainType && it.enabled }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("${chainType.displayName} network")
            if (enabledNetworks.isEmpty()) {
                WalletInfoNote(
                    "No enabled ${chainType.displayName} networks yet — add one below."
                )
            }
            enabledNetworks.forEach { record ->
                RadioRow(
                    label = record.config.name +
                        (if (record.config.isTestnet) " · testnet" else ""),
                    selected = record.config.id == active?.id,
                    onSelect = {
                        scope.launch {
                            runCatching { engine.setActiveNetwork(chainType, record.config.id) }
                                .onSuccess { onMessage("Network set to ${record.config.name}") }
                                .onFailure { onMessage("Could not switch network") }
                        }
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingActionRow(
                title = "Add network",
                subtitle = "Add a custom EVM network by chain ID",
                leadingIcon = Icons.Filled.Add,
                onClick = onAddNetwork
            )
            SettingActionRow(
                title = "Browse Chainlist",
                subtitle = "Search the network catalog",
                leadingIcon = Icons.Filled.Search,
                onClick = onBrowseChainlist
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Every site this profile has connected to, and the way back out.
 *
 * A granted permission is what makes a dApp STOP asking: once it lands, the
 * connect prompt never appears again for that host, on that chain, for that
 * account. With no place to see and revoke them the only way back is to
 * delete the wallet, so this sheet is the counterpart of the connect prompt
 * rather than a nicety.
 *
 * One row per permission — a host can hold several, one per chain, and each
 * is revoked on its own, which is exactly the granularity the engine records.
 * Disconnecting is immediate and asks for no confirmation: the site simply
 * prompts again next time, which is a recoverable outcome, and a dialog
 * stacked on a sheet is one more surface to get wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedSitesSheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val permissions by engine.dappPermissions.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Connected sites")
            if (permissions.isEmpty()) {
                WalletInfoNote(
                    "No site is connected yet. A site appears here once you approve " +
                        "its connect request — and until you disconnect it, it will " +
                        "not have to ask again."
                )
            }
            permissions.forEach { record ->
                ConnectedSiteRow(
                    record = record,
                    onDisconnect = {
                        engine.revokeDappPermission(record.host, record.chainType)
                        onMessage("Disconnected ${record.host}")
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Host, what it was granted, and the one action that takes it back.
 *
 * The granted methods are shown rather than summarised: they are the actual
 * grant, and "can sign" would be a friendlier way of saying something less
 * true. The address matters too — a host permitted on one account is not
 * permitted on another.
 */
@Composable
private fun ConnectedSiteRow(record: DappPermissionRecord, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = record.host,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = "Connected site ${record.host}"
                }
            )
            Text(
                text = "${record.chainType.displayName} · " +
                    shortenAddress(record.accountAddress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (record.methods.isNotEmpty()) {
                Text(
                    text = record.methods.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onDisconnect,
            modifier = Modifier.semantics {
                contentDescription = "Disconnect ${record.host}"
            }
        ) { Text("Disconnect") }
    }
}

/**
 * The recovery phrase, readable again from the wallet.
 *
 * Onboarding calls its reveal the one moment the phrase is in the user's hands,
 * and for a long time it was the only one: nothing else in the app could
 * produce the phrase. That made a lost piece of paper unrecoverable even though
 * the wallet was intact and the user still controlled it — the phrase was never
 * gone, only unreadable. This sheet is the second reading.
 *
 * Everything about it is built to keep the exposure as short as possible:
 *
 *  - the engine call happens on the tap, never on open, so a sheet opened by
 *    accident shows nothing;
 *  - the words are masked on arrival and need a second tap to be legible: the
 *    hidden state is the default, not something the user has to restore;
 *  - there is deliberately NO copy button. A clipboard outlives this sheet and
 *    is readable by every other app on the device, so the sealed export file —
 *    which is password-protected — stays the only way to take the phrase out
 *    of the app;
 *  - the words live in this composition's state alone. Dismissing the sheet
 *    drops them; reopening starts from the button again.
 *
 * The gate is the wallet session. [WalletEngineApi.revealMnemonic] requires a
 * bound, unlocked wallet, and the dashboard only reaches this sheet from an
 * unlocked screen — a locked wallet shows the lock screen instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevealPhraseSheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Recovery phrase")
            WalletInfoNote(
                "Anyone who reads these words owns this wallet. Check that nobody " +
                    "can see this screen, and never type them into a site or give " +
                    "them to anyone — including support."
            )
            Spacer(Modifier.height(14.dp))
            val grid = words
            if (grid == null) {
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val phrase =
                                    runCatching { engine.revealMnemonic() }.getOrNull()
                                busy = false
                                if (phrase.isNullOrBlank()) {
                                    onMessage("Could not read the recovery phrase")
                                } else {
                                    words = phrase.trim()
                                        .split(Regex("\\s+"))
                                        .filter { it.isNotEmpty() }
                                    revealed = true
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Reveal recovery phrase" }
                ) { Text(if (busy) "Reading..." else "Reveal recovery phrase") }
            } else {
                // Same word grid as the onboarding reveal, so a user who wrote
                // the phrase down there recognises what they are comparing
                // against — including the numbering.
                grid.chunked(3).forEachIndexed { rowIndex, rowWords ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowWords.forEachIndexed { column, word ->
                            val index = rowIndex * 3 + column + 1
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(extras.surfaceAlt.copy(alpha = 0.7f))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    "$index. ${if (revealed) word else "••••••"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = extras.textPrimary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = if (revealed) {
                                "Hide recovery phrase"
                            } else {
                                "Show recovery phrase"
                            }
                        }
                ) { Text(if (revealed) "Hide phrase" else "Show phrase") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Custom EVM network form: name, chain ID, RPC URL, symbol, decimals,
 * explorer — validated like the settings screens (inline errors, nothing
 * malformed is ever submitted to the engine).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkSheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var chainId by remember { mutableStateOf("") }
    var rpcUrl by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("18") }
    var explorer by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)

    val nameBlank = name.trim().isEmpty()
    val chainIdInvalid = chainId.trim().toLongOrNull()?.let { it <= 0L } ?: true
    val rpcInvalid = !httpUrl.matches(rpcUrl.trim())
    val symbolBlank = symbol.trim().isEmpty()
    // 1..36, not 0..36: the engine rejects nativeDecimals <= 0 (a 0 would
    // otherwise surface as a misleading "already exists" failure).
    val decimalsInvalid = decimals.trim().toIntOrNull()?.let { it !in 1..36 } ?: false
    val explorerInvalid = explorer.isNotBlank() && !httpUrl.matches(explorer.trim())
    val canSubmit = !nameBlank && !chainIdInvalid && !rpcInvalid && !symbolBlank &&
        !decimalsInvalid && !explorerInvalid

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Add network")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                isError = attempted && nameBlank,
                supportingText = {
                    if (attempted && nameBlank) Text("Name is required")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = chainId,
                onValueChange = { chainId = it },
                label = { Text("Chain ID (EVM)") },
                singleLine = true,
                isError = attempted && chainIdInvalid,
                supportingText = {
                    if (attempted && chainIdInvalid) Text("Numbers only, e.g. 137")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = rpcUrl,
                onValueChange = { rpcUrl = it },
                label = { Text("RPC URL (https://…)") },
                singleLine = true,
                isError = attempted && rpcInvalid,
                supportingText = {
                    if (attempted && rpcInvalid) Text("Must be an http(s) URL")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text("Native symbol") },
                singleLine = true,
                isError = attempted && symbolBlank,
                supportingText = {
                    if (attempted && symbolBlank) Text("Symbol is required, e.g. ETH")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = decimals,
                onValueChange = { decimals = it },
                label = { Text("Decimals") },
                singleLine = true,
                isError = attempted && decimalsInvalid,
                supportingText = {
                    if (attempted && decimalsInvalid) Text("1–36 (18 for most EVM chains)")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = explorer,
                onValueChange = { explorer = it },
                label = { Text("Explorer URL (optional)") },
                singleLine = true,
                isError = attempted && explorerInvalid,
                supportingText = {
                    if (attempted && explorerInvalid) Text("Must be an http(s) URL")
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        attempted = true
                        val id = chainId.trim().toLongOrNull()
                        if (id == null || id <= 0L || !canSubmit) {
                            failure = null
                        } else {
                            scope.launch {
                                val config = NetworkConfig.evm(
                                    chainId = id,
                                    name = name.trim(),
                                    rpcUrls = listOf(rpcUrl.trim()),
                                    symbol = symbol.trim(),
                                    explorer = explorer.trim().ifBlank { null }
                                ).copy(
                                    // The factory defaults to 18; honor the
                                    // field the user actually filled in.
                                    nativeDecimals = decimals.trim().toIntOrNull() ?: 18
                                )
                                runCatching { engine.addCustomNetwork(config) }
                                    .onSuccess { added ->
                                        if (added) {
                                            onMessage("Network added")
                                            onDismiss()
                                        } else {
                                            failure = "A network with this chain ID already exists"
                                        }
                                    }
                                    .onFailure { e ->
                                        failure = "Could not add network — ${e.message}"
                                    }
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Add network") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Chainlist browser: searchable list of every network the engine knows
 * (name / chain ID / symbol), an enable toggle per row, and a
 * "Refresh from Chainlist" action whose result count is reported through
 * [onMessage].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainlistSheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    val networks by engine.networks.collectAsState()
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val needle = query.trim().lowercase()
    val filtered = networks.filter { record ->
        needle.isEmpty() ||
            record.config.name.lowercase().contains(needle) ||
            record.config.chainId.lowercase().contains(needle) ||
            record.config.nativeSymbol.lowercase().contains(needle)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        // No outer verticalScroll: the list below is the scrolling element.
        Column(Modifier.padding(horizontal = 16.dp)) {
            RoomSheetHeader("Chainlist browser")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search networks") },
                singleLine = true,
                shape = RoundedCornerShape((extras.radius * 0.6f).dp),
                trailingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = extras.icon)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                EmptyState(
                    "No matching networks",
                    "Nothing in the catalog matches \"$needle\"."
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(filtered, key = { it.config.id }) { record ->
                        ChainlistRow(record = record, onChange = { enabled ->
                            scope.launch {
                                runCatching { engine.setNetworkEnabled(record.config.id, enabled) }
                                    .onFailure { onMessage("Could not update network") }
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        refreshing = true
                        runCatching { engine.refreshChainlist() }
                            .onSuccess { count ->
                                onMessage(
                                    if (count > 0) {
                                        "$count new networks from Chainlist"
                                    } else {
                                        "No new networks found"
                                    }
                                )
                            }
                            .onFailure { onMessage("Chainlist refresh failed") }
                        refreshing = false
                    }
                },
                enabled = !refreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(if (refreshing) "Refreshing…" else "Refresh from Chainlist") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One Chainlist row: name + chain/ID/symbol summary, whole row toggles enabled. */
@Composable
private fun ChainlistRow(record: NetworkRecord, onChange: (Boolean) -> Unit) {
    val extras = LocalRoomExtras.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape((extras.radius * 0.7f).dp))
            .clickable { onChange(!record.enabled) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            // The trailing Switch is display-only (the row is the target), so
            // it announces nothing on its own — the row carries the state, in
            // the same words SettingSwitchRow uses.
            .semantics {
                contentDescription =
                    "${record.config.name} network switch, ${if (record.enabled) "on" else "off"}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                record.config.name,
                style = MaterialTheme.typography.bodyLarge,
                color = extras.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    record.config.chainType.displayName,
                    record.config.chainId.take(24),
                    record.config.nativeSymbol,
                    if (record.config.isTestnet) "testnet" else null,
                    if (record.isCustom) "custom" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = extras.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Display-only: the whole row is the touch target (RadioRow pattern).
        Switch(checked = record.enabled, onCheckedChange = null)
    }
}

/**
 * Add account: one row per chain family (tap = derive the next account from
 * the wallet's recovery phrase), plus the entry into the private-key import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountSheet(
    engine: WalletEngineApi,
    onImportKey: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Add account")
            derivedAccountChains.forEach { chain ->
                SettingActionRow(
                    title = "Add ${chain.displayName} account",
                    subtitle = "Derived from this wallet's recovery phrase",
                    leadingIcon = Icons.Filled.Add,
                    onClick = {
                        scope.launch {
                            runCatching { engine.addDerivedAccount(chain) }
                                .onSuccess { account ->
                                    if (account != null) {
                                        onMessage("${chain.displayName} account added")
                                        onDismiss()
                                    } else {
                                        onMessage(
                                            "Could not derive a ${chain.displayName} account"
                                        )
                                    }
                                }
                                .onFailure {
                                    onMessage("Could not derive a ${chain.displayName} account")
                                }
                        }
                    }
                )
            }
            SettingActionRow(
                title = "Import private key",
                subtitle = "Add an existing account by its private key",
                leadingIcon = Icons.Filled.Key,
                onClick = onImportKey
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Chain families that accept raw private-key imports (per the engine contract). */
private val keyImportChains =
    listOf(ChainType.EVM, ChainType.SOLANA, ChainType.TRON, ChainType.OCTRA)

/**
 * Chains that can hold more than one DERIVED account. Octra's key comes from
 * a single HMAC over the phrase with no index to advance, so it is excluded
 * rather than offered as a row that could only fail.
 */
private val derivedAccountChains = ChainType.entries.filter { it != ChainType.OCTRA }

/** Per-chain hint for the private-key field. */
private fun privateKeyHint(chain: ChainType): String = when (chain) {
    ChainType.EVM, ChainType.TRON -> "64 hex characters, with or without the 0x prefix"
    ChainType.SOLANA -> "base58, 87–88 characters"
    ChainType.OCTRA -> "32-byte seed, base58 or hex"
    else -> "Check the ${chain.displayName} key format"
}

/**
 * Private-key import: chain chips (EVM / Solana / TRON / Octra), a MASKED key field
 * (never rendered in clear by default — same reveal-toggle pattern as the
 * password vault) with per-chain hints, and an optional account label. The
 * typed key lives only in transient composition state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportKeySheet(
    engine: WalletEngineApi,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extras = LocalRoomExtras.current
    val scope = rememberCoroutineScope()
    var chain by remember { mutableStateOf(ChainType.EVM) }
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val fieldShape = RoundedCornerShape((extras.radius * 0.6f).dp)
    val keyBlank = key.trim().isEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoomBottomSheetShape) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            RoomSheetHeader("Import private key")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                keyImportChains.forEach { candidate ->
                    FilterChip(
                        selected = candidate == chain,
                        onClick = { chain = candidate },
                        label = { Text(candidate.displayName) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Private key") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    // The shared icon button, so this control carries the same
                    // tooltip + accessible name as every other icon action.
                    WalletIconButton(
                        label = if (keyVisible) "Hide private key" else "Show private key",
                        icon = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        onClick = { keyVisible = !keyVisible }
                    )
                },
                isError = attempted && keyBlank,
                supportingText = {
                    if (attempted && keyBlank) {
                        Text("Private key is required")
                    } else {
                        Text(privateKeyHint(chain))
                    }
                },
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Account label (optional)") },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            failure?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        attempted = true
                        scope.launch {
                            runCatching {
                                engine.importAccount(
                                    chainType = chain,
                                    privateKey = key.trim(),
                                    label = label.trim().ifBlank {
                                        "${chain.displayName} import"
                                    }
                                )
                            }.onSuccess { account ->
                                if (account != null) {
                                    onMessage("Key imported")
                                    onDismiss()
                                } else {
                                    failure = "Could not import this key — check it matches " +
                                        "the ${chain.displayName} format"
                                }
                            }.onFailure { e ->
                                failure = "Could not import this key — ${e.message}"
                            }
                        }
                    },
                    enabled = !keyBlank,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Import key") }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
