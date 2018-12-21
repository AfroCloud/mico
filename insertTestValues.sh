#!/bin/bash
curl -X POST "http://localhost:8080/services" -H  "accept: application/hal+json" -H  "Content-Type: application/json" -d "{  \"shortName\": \"TestService\",  \"version\": \"1.0\"}"
curl -X POST "http://localhost:8080/services" -H  "accept: application/hal+json" -H  "Content-Type: application/json" -d "{  \"contact\": \"max@musterman.de\",  \"description\": \"This is a test service\",  \"dockerImageName\": \"TestImage\",  \"dockerImageUri\": \"http://...\",  \"name\": \"Test Service TWO\",  \"owner\": \"Max Musterman\",  \"shortName\": \"testService2\",  \"vcsRoot\": \"http://....\",  \"version\": \"1.2\"}"
