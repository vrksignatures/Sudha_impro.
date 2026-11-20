import com.vrksignatures.sudhaautobot.UpgradeRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class UpgradeSelfTest {

    private static int checks;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    public static void main(String[] args) {
        System.out.println("== UpgradeSelfTest ==");

        // 1. Ranjana Yadvendu (Dist SM65) + adjacent Ranjana Yadvendu (Retailer 65) skipped;
        //    Mushkan Kirana (SMR2255) is True Retailer #1
        List<UpgradeRules.Card> list1 = Arrays.asList(
                new UpgradeRules.Card("SM65", "RANJANA YADVENDU", true, false),
                new UpgradeRules.Card("65", "RANJANA YADVENDU", false, true),
                new UpgradeRules.Card("SMR2255", "MUSHKAN KIRANA", false, true),
                new UpgradeRules.Card("SMR2254", "OM KIRANA", false, true)
        );
        UpgradeRules.Classification c1 = UpgradeRules.classifyCards(list1);
        check(c1.allBlacklistedIds.contains("SM65"), "SM65 blacklisted");
        check(c1.allBlacklistedIds.contains("65"), "Adjacent same-name retailer 65 blacklisted");
        check(c1.genuineRetailers.size() == 2 && "SMR2255".equals(c1.genuineRetailers.get(0).customerId),
                "SMR2255 is True Retailer #1");

        // 2. Edge Case A: non-adjacent same-name genuine retailer NOT skipped
        List<UpgradeRules.Card> list2 = Arrays.asList(
                new UpgradeRules.Card("SM41", "TUNTUN KUMAR", true, false),
                new UpgradeRules.Card("41", "TUNTUN KUMAR", false, true),
                new UpgradeRules.Card("M23", "OM KIRANA", false, true),
                new UpgradeRules.Card("900", "TUNTUN KUMAR", false, true)
        );
        UpgradeRules.Classification c2 = UpgradeRules.classifyCards(list2);
        check(c2.allBlacklistedIds.contains("41"), "Adjacent 41 blacklisted");
        check(!c2.allBlacklistedIds.contains("900"), "Non-adjacent 900 NOT blacklisted");
        check(c2.genuineRetailers.size() == 2, "M23 and 900 both genuine");

        // 3. Edge Case B: zero-distributor list starts immediately at Card #1
        List<UpgradeRules.Card> list3 = Arrays.asList(
                new UpgradeRules.Card("101", "ALPHA DAIRY", false, true),
                new UpgradeRules.Card("102", "BETA KIRANA", false, true)
        );
        UpgradeRules.Classification c3 = UpgradeRules.classifyCards(list3);
        check(c3.distributorIds.isEmpty() && c3.genuineRetailers.size() == 2, "Zero-dist list OK");

        // 4. Employee's Chance anti-bot scatter: exact sum & non-consecutive
        Map<Integer, Integer> plan = UpgradeRules.generateScatteredPlan("2026-11-20", 10000, 15, 125);
        int sum = 0;
        List<Integer> keys = new ArrayList<>(plan.keySet());
        for (int v : plan.values()) sum += v;
        check(plan.size() == 15, "Picked 15 retailers");
        check(sum == 10000, "Exact 10000 ml sum");
        check(keys.get(keys.size() - 1) > 30, "Scattered across range, not consecutive 1..15");

        // 5. Customer Demand smart pack math (+1000ml on active 500ml -> +2 packs)
        int[] delta = UpgradeRules.resolvePackDelta(1000, 0, 4);
        check(delta != null && delta[0] == 500 && delta[1] == 6, "COW 500 ML 4 -> 6");

        System.out.println("CHECKS " + checks + " passed");
    }
}
