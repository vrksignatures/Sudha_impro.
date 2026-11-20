import com.vrksignatures.sudhaautobot.AccountId;
import com.vrksignatures.sudhaautobot.AccountId.Kind;
import com.vrksignatures.sudhaautobot.RetailerPolicy;
import com.vrksignatures.sudhaautobot.Roster;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The roster is read off the screen, not assumed.
 *
 * Checks the id rule (SMR = retailer; SM or digits only = distributor), that the number of
 * retailers follows what the Sudha app prints - 125, 300 or 40 - and that a change of
 * distributor is noticed. Then runs the bot's real list loop over lists of other sizes with
 * the setup figure left at 125.
 */
public final class RosterSelfTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        idRule();
        idsInText();
        countFollowsTheApp();
        collapsedListIsNotTheCount();
        distributorChange();
        survivesARestart();
        newDay();
        wholeLists();

        if (failures > 0) {
            System.out.println(failures + " OF " + checks + " CHECKS FAILED");
            System.exit(1);
        }
        System.out.println("ALL " + checks + " CHECKS PASSED");
    }

    private static void idRule() {
        String[] retailers = {"SMR2196", "smr1", "SMR999999", " SMR41 ", "XSMR7"};
        String[] distributors = {"SM65", "SM41", "sm7", "65", "000123", "SM0", "ASM12"};
        String[] neither = {"", null, "Asmita", "SMR", "SM", "Ranjana Yadvendu", "Tuntun", "SM-65",
                "R2196", "12a", "Total Customers: 125", "smart"};
        for (String id : retailers) check(id + " is a retailer", AccountId.kindOf(id) == Kind.RETAILER);
        for (String id : distributors) check(id + " is a distributor", AccountId.kindOf(id) == Kind.DISTRIBUTOR);
        for (String id : neither) check(id + " is neither", AccountId.kindOf(id) == Kind.OTHER);

        // SMR is tested before SM: every SMR id also contains SM.
        check("SMR wins over SM", AccountId.isRetailer("SMR65") && !AccountId.isDistributor("SMR65"));

        // What the bot may open has not been widened: a distributor is never a row to open.
        for (String id : distributors) {
            check(id + " is never opened", !RetailerPolicy.isRetailerId(id.trim().toUpperCase()));
        }
        for (int i = 0; i < 400; i++) {
            String id = "SMR" + (1 + i * 37);
            check(id + " agrees with the bot's own rule",
                    AccountId.isRetailer(id) && RetailerPolicy.isRetailerId(id));
        }
    }

    private static void idsInText() {
        List<String> ids = AccountId.retailerIds(
                "customer id: smr2196\nsm65\ncustomer id: smr2195\nsmr2196\nasmr12\nsmr12x\n(smr7)");
        check("ids are read in order, once each", ids.toString().equals("[SMR2196, SMR2195, SMR7]"));
        check("no ids in no text", AccountId.retailerIds(null).isEmpty() && AccountId.retailerIds("").isEmpty());
    }

    private static String listScreen(int header, int firstRow, int rows) {
        StringBuilder text = new StringBuilder("customers\ntotal customers: " + header
                + "\nall distributors\nshow retailers\nsm65\n");
        for (int i = 0; i < rows; i++) text.append("customer id: smr").append(firstRow + i).append('\n');
        return text.toString();
    }

    private static void countFollowsTheApp() {
        for (int size : new int[]{125, 300, 40, 1, 999}) {
            Roster roster = new Roster();
            check(size + ": before anything is read, the setup figure stands", roster.expected(125) == 125);
            roster.see(listScreen(size, 1000, Math.min(size, 7)), "d1");
            if (size >= 3) {
                check(size + ": the app's figure is used", roster.expected(125) == size);
                check(size + ": and trusted", roster.headerTrusted() && roster.total() == size);
            } else {
                check(size + ": too few rows to trust a figure", roster.expected(125) == 125);
            }
        }

        // No figure printed at all (the app drops the header): never fewer than were seen.
        Roster blind = new Roster();
        for (int page = 0; page < 30; page++) {
            StringBuilder text = new StringBuilder("customers\n");
            for (int i = 0; i < 7; i++) text.append("smr").append(5000 + page * 7 + i).append('\n');
            blind.see(text.toString(), "d1");
        }
        check("no header: 210 seen beats a setup figure of 125", blind.expected(125) == 210);
        check("no header: a larger setup figure still stands", blind.expected(300) == 300);

        // A header smaller than what has been seen is plainly not the retailer count.
        Roster odd = new Roster();
        odd.see(listScreen(3, 1, 7), "d1");
        check("a header below the rows seen is not trusted", !odd.headerTrusted() && odd.expected(125) == 125);
    }

    /** The list as it opens: collapsed to the distributors, "Total Customers: 2". */
    private static void collapsedListIsNotTheCount() {
        Roster roster = new Roster();
        roster.see("customers\ntotal customers: 2\nall distributors\nshow retailers\nsm65\nsm41\n", "d1");
        check("collapsed list: figure ignored", roster.header() == 0 && roster.expected(125) == 125);
        roster.see("home\ntotal customers: 2\ndaily orders\n", "d1");
        check("home screen: figure ignored", roster.header() == 0);
        roster.see(listScreen(300, 1, 7), "d1");
        check("expanded list: figure taken", roster.expected(125) == 300);
        roster.see("customers\ntotal customers: 2\nall distributors\nshow retailers\nsm65\n", "d1");
        check("collapsing again does not lower it", roster.expected(125) == 300);
    }

    private static void distributorChange() {
        Roster roster = new Roster();
        roster.see("customer details\nsmr2196\ndistributor name ( sm65 )\nranjana yadvendu\nview orders\nmilk\n", "d1");
        check("distributor read", roster.distributorId().equals("SM65")
                && roster.distributorName().equals("Ranjana Yadvendu"));
        check("no change yet", roster.distributorBefore().isEmpty());
        roster.see("customer details\nsmr2196\ndistributor name ( sm65 )\nranjana yadvendu\nview orders\n", "d1");
        check("seeing it again changes nothing", roster.distributorBefore().isEmpty());

        roster.see("customer details\nsmr2196\ndistributor name ( sm41 )\ntuntun\nview orders\nmilk\n", "d2");
        check("new distributor read", roster.distributorId().equals("SM41")
                && roster.distributorName().equals("Tuntun"));
        check("the earlier one is remembered", roster.distributorBefore().equals("Ranjana Yadvendu (SM65)"));
        check("described", roster.describe().contains("Tuntun (SM41)"));

        Roster digits = new Roster();
        digits.see("customer details\ndistributor name ( 65 )\nview orders\n", "d1");
        check("a digits-only id is a distributor", digits.distributorId().equals("65"));
        check("a label under it is not taken for a name", digits.distributorName().isEmpty());

        Roster retailer = new Roster();
        retailer.see("customer details\ndistributor name ( smr65 )\n", "d1");
        check("an SMR id is never a distributor", retailer.distributorId().isEmpty());
    }

    private static void survivesARestart() {
        Roster roster = new Roster();
        roster.see(listScreen(300, 1, 7), "d1");
        roster.see("customer details\ndistributor name ( sm41 )\ntuntun\nview orders\n", "d1");
        check("something to save", roster.takeDirty());
        check("and then nothing new", !roster.takeDirty());
        Roster again = Roster.decode(roster.encode());
        check("count kept", again.expected(125) == 300 && again.seenToday() == 7);
        check("distributor kept", again.distributorLabel().equals("Tuntun (SM41)"));
        check("rubbish is survived", Roster.decode("header=abc\nseen=;;;\n\u0000").expected(125) == 125
                && Roster.decode(null).expected(125) == 125 && Roster.decode("").expected(77) == 77);
        check("an absurd saved figure is dropped", Roster.decode("day=d1\nheader=999999\n").expected(125) == 125);
    }

    private static void newDay() {
        Roster roster = new Roster();
        roster.see(listScreen(300, 1, 7), "d1");
        roster.see("home\n", "d2");
        check("a new day starts from the setup figure", roster.expected(125) == 125 && roster.seenToday() == 0);
        check("yesterday's figure is only remembered", roster.lastTotal() == 300);
        roster.see(listScreen(40, 1, 7), "d2");
        check("a list that shrank is followed", roster.expected(125) == 40);
    }

    /** The bot's own list loop, with the setup figure left at 125 and the count read live. */
    private static void wholeLists() {
        for (int size : new int[]{40, 125, 126, 300}) {
            for (int headerStyle = 0; headerStyle < 3; headerStyle++) {
                RetailerListModel model = new RetailerListModel(size);
                // The figure printed: retailers only, retailers + the distributor, or none usable.
                model.headerCount = headerStyle == 0 ? size : headerStyle == 1 ? size + 1 : 2;
                ListRunner runner = new ListRunner(model, true);
                runner.roster = new Roster();
                runner.configuredCount = 125;
                runner.start();
                runner.run(200_000);
                String name = size + " retailers, header " + model.headerCount;
                check(name + ": the run ends", runner.finished);
                check(name + ": every retailer handled", runner.done.size() == size);
                check(name + ": each exactly once",
                        new LinkedHashSet<>(runner.opened).size() == runner.opened.size()
                                && runner.opened.size() == size);
                check(name + ": the distributor is never opened", !runner.opened.contains("SM65"));
                check(name + ": at most one extra pass", runner.sweeps <= 1);
                if (headerStyle == 0) {
                    check(name + ": expected count read from the app", runner.expectedCount == size);
                    check(name + ": no extra pass needed", runner.sweeps == 0);
                }
                if (headerStyle == 2) {
                    check(name + ": expected count never below what was seen",
                            runner.expectedCount == Math.max(125, size));
                }
            }
        }
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
