package org.cloudbus.cloudsim.cosched.workload;

import java.util.ArrayList;
import java.util.List;

/**
 * Synergy's model assignment, mode {@code OVERALL} (the mode its simulator uses,
 * {@code jobs/workload.py}): each job goes to the class (image / language / speech) furthest below
 * its target share of all jobs labelled so far, and within the class to the next model round-robin.
 *
 * <p>Priority of a class = target percent / 100 - (jobs of that class / all jobs). The highest
 * priority wins; ties go to the earlier class in the list (Python's stable sort in
 * {@code ModelZoo.get_job_class}). Before the first job every priority is 0, so the first job is
 * from the first class. This depends only on the order of jobs, so it is deterministic; Synergy's
 * random draw in that method is overridden in this mode.
 *
 * <p>Default classes and models are Synergy's ({@code jobs/model_zoo.py, create_default_models}).
 */
public final class ClassSplitLabeler implements ModelLabeler {

    /** A class of models and its target share of jobs. */
    public record ModelClass(String name, double percent, List<String> models) {
        public ModelClass {
            if (percent < 0) throw new IllegalArgumentException("class " + name + ": percent must be >= 0");
            if (models == null || models.isEmpty()) throw new IllegalArgumentException("class " + name + " needs at least one model");
            models = List.copyOf(models);
        }
    }

    public static final List<String> SYNERGY_IMAGE = List.of("alexnet", "res18", "res50", "mobilenet", "shufflenet");
    public static final List<String> SYNERGY_LANGUAGE = List.of("gnmt", "transformer", "lstm");
    public static final List<String> SYNERGY_SPEECH = List.of("m5", "deepspeech");

    private final List<ModelClass> classes;
    private final int[] jobsInClass;
    private final int[] nextModel;
    private int total;

    public ClassSplitLabeler(List<ModelClass> classes) {
        if (classes.isEmpty()) throw new IllegalArgumentException("need at least one model class");
        double sum = classes.stream().mapToDouble(ModelClass::percent).sum();
        if (Math.abs(sum - 100) > 1e-6) throw new IllegalArgumentException("class percentages must add up to 100, got " + sum);
        this.classes = List.copyOf(classes);
        this.jobsInClass = new int[classes.size()];
        this.nextModel = new int[classes.size()];
    }

    /** Synergy's three classes with the given split, e.g. (20, 70, 10). */
    public static ClassSplitLabeler synergy(double image, double language, double speech) {
        return new ClassSplitLabeler(List.of(
                new ModelClass("image", image, SYNERGY_IMAGE),
                new ModelClass("language", language, SYNERGY_LANGUAGE),
                new ModelClass("speech", speech, SYNERGY_SPEECH)));
    }

    @Override
    public String label(JobRecord job) {
        int best = 0;
        double bestPriority = priority(0);
        for (int c = 1; c < classes.size(); c++) {
            double p = priority(c);
            if (p > bestPriority) {
                best = c;
                bestPriority = p;
            }
        }
        List<String> models = classes.get(best).models();
        String model = models.get(nextModel[best] % models.size());
        nextModel[best]++;
        jobsInClass[best]++;
        total++;
        return model;
    }

    /** Same arithmetic as Synergy's {@code Task.update_priority}; 0 before any job. */
    private double priority(int c) {
        if (total == 0) return 0;
        return classes.get(c).percent() / 100 - (double) jobsInClass[c] / total;
    }

    /** Jobs labelled per class so far, in class order. */
    public List<Integer> counts() {
        List<Integer> l = new ArrayList<>();
        for (int n : jobsInClass) l.add(n);
        return l;
    }
}
