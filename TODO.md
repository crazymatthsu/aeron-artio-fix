### Artio FIX engine 
- FIX 42/44 engine that can connect to other FIX engines as initiator or acceptor. 
- understand QuickFIX/J FIX specs and can convert QuickFIX/J FIX specs to Artio FIX specs.
- can be used to receive FIX messages from other FIX engines and publish to AMPS topics.
- analyze can the Artio FIX engine be run as Spring Boot application and can be used to receive FIX messages from other FIX engines and publish to AMPS topics.

#### Artio FIX engine to AMPS bridge
- create a AMPS publisher that will attach to this Artio FIX engine and publish FIX messages to AMPS topics.
- AMPS topic is FIX message format 
- AMPS docker image can be based on /Users/maojenhsu/ai-code/amps-demo project , which runs AMPS server in a podman container locally
- there are some details logged in docs/aeron_artio_amps_analysis.md about how to integrate Artio FIX engine with AMPS topics.

#### preference 
- prefer podman compose to spin up AMPS server for integration testing, and use local podman container for AMPS server for integration testing.
- README.md should have architecture diagram to show components and how they interact with each other via different protocols (FIX, AMPS, etc.)
- 
#### project structure 
- java 21 
- gradle to build 
- multiple submodules for different use cases
- docs folder will have detailed .md analysis files 
- build unit tests and integration tests for each submodule
- create README.md for each submodule with details about the use case and how to run the demo.
- 