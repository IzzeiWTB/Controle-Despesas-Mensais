import { spawn } from 'node:child_process';
import path from 'node:path';
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const child = spawn(java, ['-jar', 'backend/target/despesas-pessoais-0.0.1-SNAPSHOT.jar'], {
  stdio: 'inherit',
  env: {
    ...process.env,
    PORT: '18765',
    APP_API_KEY: 'browser-test-only',
    SPRING_DATASOURCE_URL: 'jdbc:h2:mem:clareza-browser;DB_CLOSE_DELAY=-1',
    SPRING_DATASOURCE_DRIVER: 'org.h2.Driver',
    SPRING_DATASOURCE_USERNAME: 'sa',
    SPRING_DATASOURCE_PASSWORD: '',
    SPRING_JPA_HIBERNATE_DDL_AUTO: 'create-drop'
  }
});
child.on('exit', code => process.exit(code ?? 1));
process.on('SIGTERM', () => child.kill());
process.on('SIGINT', () => child.kill());
