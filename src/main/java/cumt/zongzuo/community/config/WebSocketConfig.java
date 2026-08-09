package cumt.zongzuo.community.config;

import cumt.zongzuo.community.websocket.WebSocketServer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
public class WebSocketConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    @Bean
    public ServerEndpointConfig webSocketServerEndpointConfig(AutowireCapableBeanFactory beanFactory) {
        return ServerEndpointConfig.Builder.create(WebSocketServer.class, "/im/{ticket}")
                .configurator(new SpringBeanEndpointConfigurator(beanFactory))
                .build();
    }

    private static final class SpringBeanEndpointConfigurator extends ServerEndpointConfig.Configurator {

        private final AutowireCapableBeanFactory beanFactory;

        private SpringBeanEndpointConfigurator(AutowireCapableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            try {
                return beanFactory.createBean(endpointClass);
            } catch (BeansException exception) {
                InstantiationException failure = new InstantiationException(
                        "Unable to create WebSocket endpoint " + endpointClass.getName());
                failure.initCause(exception);
                throw failure;
            }
        }
    }
}
