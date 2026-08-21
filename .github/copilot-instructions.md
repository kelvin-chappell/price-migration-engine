This is a lease and replay orchestration engine that repeatedly runs several AWS lambdas in a state engine
in order to migrate subscription prices.
There should be a clear separation between pure and effectful code and pure code
should be extracted wherever possible.
There should be no use of relative times in the codebase.
Instead, use the Clock service.
Logging is also considered to be an effect.
