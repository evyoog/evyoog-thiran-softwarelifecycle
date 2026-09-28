package com.vyoog.importqueue.prd;

import com.vyoog.portfolio.Application;
import com.vyoog.portfolio.ApplicationRepository;
import com.vyoog.portfolio.Capability;
import com.vyoog.portfolio.CapabilityRepository;
import com.vyoog.portfolio.Product;
import com.vyoog.portfolio.ProductRepository;
import com.vyoog.requirements.Placement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns the hierarchy names a PRD template spells out into the ids the register uses.
 *
 * <p>Resolution is by name within the parent that was named, never globally: two
 * products can both have a "Reporting" application, and matching on the leaf alone
 * would file requirements under whichever happened to be found first. So the product is
 * resolved, then the application within it, then the capability within that — and a
 * break anywhere down that chain stops the walk rather than falling back to a looser
 * match.
 *
 * <p>Nothing is created here. A name that does not exist is reported, not invented: the
 * portfolio is the shape of somebody's product, and letting a typo in a spreadsheet cell
 * silently add a capability to it would make the hierarchy an accident of import history.
 * The user is told exactly which names are unknown so they can either fix the cell or
 * create the node in Portfolio deliberately.
 */
@Service
public class PrdTemplateResolver {

    private final ProductRepository products;
    private final ApplicationRepository applications;
    private final CapabilityRepository capabilities;

    public PrdTemplateResolver(ProductRepository products, ApplicationRepository applications,
                                CapabilityRepository capabilities) {
        this.products = products;
        this.applications = applications;
        this.capabilities = capabilities;
    }

    /**
     * One row's placement, plus whatever stopped it being a full one.
     *
     * @param placement where the row lands — {@link Placement#unplaced()} when the names
     *                  could not be walked, so the row is still importable once a human
     *                  picks a capability by hand
     * @param problems  the unresolved names, phrased for the person holding the sheet
     */
    public record Resolved(Placement placement, List<String> problems) {}

    /**
     * Resolves against the application the batch was uploaded to when the sheet names no
     * product, so a single-application import does not have to repeat the hierarchy on
     * every row.
     *
     * @param fallbackApplicationId the batch's application, or null
     */
    public Resolved resolve(PrdRow row, UUID fallbackApplicationId) {
        List<String> problems = new ArrayList<>();

        Optional<Product> product = row.product().isEmpty()
            ? Optional.empty()
            : products.findAllByArchivedAtIsNullOrderByNameAsc().stream()
                .filter(p -> same(p.getName(), row.product())).findFirst();
        if (row.product().isEmpty() || product.isEmpty()) {
            if (!row.product().isEmpty()) {
                problems.add("No product named \"%s\" — create it in Portfolio, or correct the cell."
                    .formatted(row.product()));
            }
            // Without a product there is no scope in which to look up the application, so
            // the walk stops here rather than guessing at a same-named app elsewhere.
            return unresolved(fallbackApplicationId, row, problems);
        }

        Optional<Application> application = applications
            .findAllByProductIdAndArchivedAtIsNull(product.get().getId()).stream()
            .filter(a -> same(a.getName(), row.application())).findFirst();
        if (application.isEmpty()) {
            problems.add("Product \"%s\" has no app named \"%s\"."
                .formatted(row.product(), row.application()));
            return new Resolved(Placement.product(product.get().getId()), List.copyOf(problems));
        }

        Optional<Capability> capability = capabilities
            .findAllByApplicationIdAndArchivedAtIsNull(application.get().getId()).stream()
            .filter(c -> same(c.getName(), row.capability())).findFirst();
        if (capability.isEmpty()) {
            // The app resolved, so the requirement still has a real home — it lands at
            // application level and says why, rather than being refused outright.
            problems.add("App \"%s\" has no capability named \"%s\"."
                .formatted(row.application(), row.capability()));
            return new Resolved(Placement.application(application.get().getId()), List.copyOf(problems));
        }

        return new Resolved(Placement.capability(capability.get().getId()), List.copyOf(problems));
    }

    private static Resolved unresolved(UUID fallbackApplicationId, PrdRow row, List<String> problems) {
        if (fallbackApplicationId == null) {
            return new Resolved(Placement.unplaced(), List.copyOf(problems));
        }
        // The batch was uploaded against an application, so a row that names no product
        // means "the one I am importing into" rather than "nowhere".
        return new Resolved(Placement.application(fallbackApplicationId), List.copyOf(problems));
    }

    /** Names are compared case- and whitespace-insensitively; people do not type them twice alike. */
    private static boolean same(String a, String b) {
        return a != null && b != null
            && a.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                .equals(b.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }
}
