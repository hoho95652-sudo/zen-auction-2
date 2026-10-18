package com.example.donutauction;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionManager {
    // CHAT mode: "Steve » bid 500k", "<Steve> bid $1,250"
    private static final Pattern CHAT_BID = Pattern.compile(
            "(?:^|[\\s<\\]\\[])([A-Za-z0-9_]{3,16})\\s*[>»:\\-]*\\s*(?:.*?\\s)?bid\\s+\\$?([0-9][0-9,.]*\\s?[kKmMbB]?)\\s*$");

    // PAY mode: reads the server's payment notice sent to YOU. If DonutSMP words it differently,
    // edit these two patterns (group 1 = payer, group 2 = amount).
    private static final Pattern PAID_YOU = Pattern.compile(
            "([A-Za-z0-9_]{3,16})\\s+(?:has\\s+)?paid\\s+you\\s+\\$?([0-9][0-9,.]*\\s?[kKmMbB]?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIVED_FROM = Pattern.compile(
            "(?:you\\s+)?received\\s+\\$?([0-9][0-9,.]*\\s?[kKmMbB]?)\\s+from\\s+([A-Za-z0-9_]{3,16})", Pattern.CASE_INSENSITIVE);

    public static boolean payMode = true;   // true = bids are /pay <seller> <amount>, false = chat "bid <amount>"
    public static boolean active = false;
    public static ItemStack item = ItemStack.EMPTY;
    public static long endTimeMs;
    public static long highestBid;
    public static String highestBidder = null;

    private static long startBid;
    private static String seller = "";
    private static int lastAnnouncedSecond = -1;
    private static final Map<String, Long> paid = new LinkedHashMap<>(); // payer -> total paid this auction

    public static void start(Minecraft mc, ItemStack stack, int seconds, long startingBid) {
        if (mc.player == null || active) return;
        item = stack.copy();
        seller = mc.getUser().getName();
        startBid = startingBid;
        highestBid = startingBid - 1;
        highestBidder = null;
        paid.clear();
        endTimeMs = System.currentTimeMillis() + seconds * 1000L;
        lastAnnouncedSecond = seconds;
        active = true;
        String how = payMode
                ? "Bid by paying: /pay " + seller + " <amount> (your payments add up, highest total wins)"
                : "Type: bid <amount>";
        say(mc, "[AUCTION] " + item.getHoverName().getString() + " | Start: $" + fmt(startBid)
                + " | " + seconds + "s | " + how);
    }

    public static void cancel(Minecraft mc) {
        if (!active) return;
        active = false;
        say(mc, "[AUCTION] Cancelled.");
        refundReminder(mc, null);
    }

    public static int secondsLeft() {
        return (int) Math.max(0, Math.ceil((endTimeMs - System.currentTimeMillis()) / 1000.0));
    }

    public static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        int left = secondsLeft();
        if (left <= 0) {
            active = false;
            if (highestBidder == null) {
                say(mc, "[AUCTION] Ended with no valid bids on " + item.getHoverName().getString() + ".");
            } else {
                say(mc, "[AUCTION] SOLD! " + item.getHoverName().getString() + " goes to " + highestBidder
                        + " for $" + fmt(highestBid) + "."
                        + (payMode ? " Payment received." : " Pay with /pay " + seller + " " + highestBid));
            }
            refundReminder(mc, highestBidder);
            return;
        }
        if (left != lastAnnouncedSecond && (left == 10 || left == 5 || left == 3)) {
            lastAnnouncedSecond = left;
            String top = highestBidder == null ? "no bids yet" : highestBidder + " @ $" + fmt(highestBid);
            say(mc, "[AUCTION] " + left + "s left - " + top);
        }
    }

    public static void onChat(String raw) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String me = mc.getUser().getName();
        if (raw.startsWith("[AUCTION]")) return;

        if (payMode) {
            String payer = null; long amt = -1;
            Matcher a = PAID_YOU.matcher(raw);
            if (a.find()) { payer = a.group(1); amt = parseAmount(a.group(2)); }
            else {
                Matcher b = RECEIVED_FROM.matcher(raw);
                if (b.find()) { payer = b.group(2); amt = parseAmount(b.group(1)); }
            }
            if (payer == null || amt <= 0 || payer.equalsIgnoreCase(me)) return;
            long total = paid.merge(payer, amt, Long::sum);
            if (total < startBid) {
                say(mc, "[AUCTION] " + payer + " paid $" + fmt(total) + " total - minimum is $" + fmt(startBid));
            } else if (total > highestBid) {
                highestBid = total; highestBidder = payer;
                say(mc, "[AUCTION] New high bid: " + payer + " - $" + fmt(total));
            }
            return;
        }

        Matcher m = CHAT_BID.matcher(raw.trim());
        if (!m.find()) return;
        String bidder = m.group(1);
        if (bidder.equalsIgnoreCase(me)) return;
        long amount = parseAmount(m.group(2));
        if (amount < startBid || amount <= highestBid) return;
        highestBid = amount; highestBidder = bidder;
        say(mc, "[AUCTION] New high bid: " + bidder + " - $" + fmt(amount));
    }

    /** Local-only (not sent to chat): who you still owe money back to. The mod never sends /pay itself. */
    private static void refundReminder(Minecraft mc, String winner) {
        if (!payMode || mc.player == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : paid.entrySet()) {
            if (winner != null && e.getKey().equalsIgnoreCase(winner)) continue;
            sb.append("\n  /pay ").append(e.getKey()).append(' ').append(e.getValue());
        }
        if (sb.length() > 0) mc.gui.getChat().addMessage(Component.literal("[Auction] Refunds owed:" + sb));
    }

    public static long parseAmount(String s) {
        s = s.replace(",", "").replace(" ", "").toLowerCase(Locale.ROOT);
        double mult = 1;
        if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
        try { return (long) (Double.parseDouble(s) * mult); } catch (NumberFormatException e) { return -1; }
    }

    public static String fmt(long v) {
        if (v >= 1_000_000_000L) return trim(v / 1e9) + "B";
        if (v >= 1_000_000L) return trim(v / 1e6) + "M";
        if (v >= 1_000L) return trim(v / 1e3) + "K";
        return Long.toString(v);
    }
    private static String trim(double d) { return d == Math.floor(d) ? Long.toString((long) d) : String.format("%.2f", d); }

    private static void say(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.connection.sendChat(msg);
    }
}
