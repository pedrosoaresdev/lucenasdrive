# Firebase Emulator Suite para desenvolvimento local (Auth, Firestore, Storage).
FROM node:24-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless && rm -rf /var/lib/apt/lists/*
WORKDIR /firebase
COPY package.json package-lock.json ./
RUN npm ci
COPY firebase.json firestore.rules firestore.indexes.json storage.rules .firebaserc ./
EXPOSE 4000 8085 9099 9199
CMD ["npx", "firebase", "emulators:start", "--project", "demo-lucena-drive", "--only", "auth,firestore,storage,ui"]
