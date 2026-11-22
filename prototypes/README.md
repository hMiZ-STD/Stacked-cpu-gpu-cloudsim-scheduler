# Check 1 prototypes

Five small CloudSim programs written to test the design before building it (architecture Check 1).
Kept as a historical record; they are not part of the Maven build.

| File | Question it answered |
|---|---|
| `CheckA.java` | Is one VM per job fast enough? Create, run and destroy up to 100,000 jobs; no leaked VMs. |
| `CheckB.java` | Can a running VM be resized? One job at 1000, 2000, then 500 MIPS. |
| `CheckC.java` | Are finish times exact when speed changes every round? 2,000 jobs, 6,876 resizes. |
| `CheckD.java` | Can a placement policy follow the plan exactly? Compared with CloudSim's default policy. |
| `CheckE.java` | Do spread jobs progress in lock-step? |

The behaviour they established is now covered by `PlanExecutorContractTest` in `modules/cloudsim-gpu`.
