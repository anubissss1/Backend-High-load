# Measurements

`latest.csv` is generated **only** by running the benchmark. No fabricated benchmark numbers are included.

Run the server in one terminal and `mvn -q exec:java -Dexec.mainClass=com.example.tasktracker.benchmark.ProtocolBenchmark -Dexec.args="1000 200"` in another. Paste generated results into the SIS submission with CPU, OS, JVM, localhost/network, number of samples and protocol versions.
