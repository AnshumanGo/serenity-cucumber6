package net.serenitybdd.cucumber.suiteslicing;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.gherkin.messages.internal.gherkin.GherkinDocumentBuilder;
import io.cucumber.core.gherkin.messages.internal.gherkin.Parser;
import io.cucumber.core.gherkin.messages.internal.gherkin.TokenMatcher;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.messages.IdGenerator;
import io.cucumber.messages.Messages.GherkinDocument.Builder;
import io.cucumber.messages.Messages.GherkinDocument.Feature;
import io.cucumber.messages.Messages.GherkinDocument.Feature.Scenario;
import net.thucydides.core.util.Inflector;
import org.codehaus.groovy.ast.builder.AstBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

public class ScenarioLineCountStatistics implements TestStatistics {

    private final Supplier<ClassLoader> classLoader = CucumberScenarioLoader.class::getClassLoader;
    private final FeatureParser parser = new FeatureParser(UUID::randomUUID);
    private final List<TestScenarioResult> results;

    private ScenarioLineCountStatistics(List<URI> featurePaths) {
        Options featureOptions = () -> featurePaths;
        //Parser<GherkinDocument> gherkinParser = new Parser<>(new AstBuilder());

        TokenMatcher matcher = new TokenMatcher();
        FeaturePathFeatureSupplier supplier =
            new FeaturePathFeatureSupplier(classLoader, featureOptions, parser);
        List<Feature> features = new ArrayList<>();
        List<io.cucumber.core.gherkin.Feature> gherkinFeatures = supplier.get();
        for(io.cucumber.core.gherkin.Feature gherkinFeature  : gherkinFeatures) {
            Parser gherkinParser = new Parser(new GherkinDocumentBuilder(new IdGenerator.UUID()));
            Builder builder = (Builder)gherkinParser.parse(gherkinFeature.getSource(), matcher);
            features.add(builder.build().getFeature());
        }
        //List<Feature> features = supplier.get().stream().map(feature -> gherkinParser.parse(feature.getSource(), matcher).getFeature()).collect(Collectors.toList());
        this.results = features.stream()
            .map(featureToScenarios())
            .flatMap(List::stream)
            .collect(toList());
    }

    public static ScenarioLineCountStatistics fromFeaturePath(URI featurePaths) {
        return fromFeaturePaths(asList(featurePaths));
    }

    public static ScenarioLineCountStatistics fromFeaturePaths(List<URI> featurePaths) {
        return new ScenarioLineCountStatistics(featurePaths);
    }

    private Function<Feature, List<TestScenarioResult>> featureToScenarios() {
        return cucumberFeature -> {
            try {
                return (cucumberFeature == null) ? Collections.emptyList() : cucumberFeature.getChildrenList()
                    .stream()
                        .filter(child -> child.hasScenario()).map(Feature.FeatureChild::getScenario)
                    //.filter(child -> asList(ScenarioOutline.class, Scenario.class).contains(child.getClass()))
                    .map(scenarioToResult(cucumberFeature))
                    .collect(toList());
            } catch (Exception e) {
                throw new IllegalStateException(String.format("Could not extract scenarios from %s", cucumberFeature.getName()), e);
            }
        };
    }

    private Function<Scenario, TestScenarioResult> scenarioToResult(Feature feature) {
        return scenarioDefinition -> {
            try {
                return new TestScenarioResult(
                    feature.getName(),
                    scenarioDefinition.getName(),
                    scenarioStepCountFor(backgroundStepCountFor(feature), scenarioDefinition));
            } catch (Exception e) {
                throw new IllegalStateException(String.format("Could not determine step count for scenario '%s'", scenarioDefinition.getDescription()), e);
            }
        };
    }

    private BigDecimal scenarioStepCountFor(int backgroundStepCount, Scenario scenarioDefinition) {
        final int stepCount;
        if (scenarioDefinition.getExamplesCount() > 0) {
            Integer exampleCount = scenarioDefinition.getExamplesList().stream().map(examples -> examples.getTableBodyList().size()).mapToInt(Integer::intValue).sum();
            stepCount = exampleCount * (backgroundStepCount + scenarioDefinition.getStepsCount());
        } else {
            stepCount = backgroundStepCount + scenarioDefinition.getStepsCount();
        }
        return BigDecimal.valueOf(stepCount);
    }

    private int backgroundStepCountFor(Feature feature) {
        Feature.FeatureChild scenarioDefinition = feature.getChildrenList().get(0);
        if (scenarioDefinition.hasBackground()) {
            return scenarioDefinition.getBackground().getStepsCount();
        } else {
            return 0;
        }
    }

    @Override
    public BigDecimal scenarioWeightFor(String feature, String scenario) {
        return results.stream()
            .filter(record -> record.feature.equals(feature) && record.scenario.equals(scenario))
            .map(TestScenarioResult::duration)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(String.format("no result found for scenario '%s' in feature '%s'", scenario, feature)));
    }

    @Override
    public List<TestScenarioResult> records() {
        return results;
    }

    public String toString() {
        return Inflector.getInstance().kebabCase(this.getClass().getSimpleName());
    }
}
