package org.cloudbus.cloudsim.cosched.workload;

/**
 * Uses the model written in the job CSV. Used to replay a run job by job (validation Part A) and
 * for traces that record the model (Alibaba PAI's workload tag). A job without a model is an error.
 */
public final class FromTraceLabeler implements ModelLabeler {

    @Override
    public String label(JobRecord job) {
        if (job.model() == null)
            throw new IllegalArgumentException("job CSV line " + job.line() + " (job " + job.jobId()
                    + ") has no model; the from_trace labeller needs one for every job");
        return job.model();
    }
}
