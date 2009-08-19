/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the license at
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package org.glassfish.webservices;

import com.sun.enterprise.deployment.WebServiceEndpoint;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * {@link WebServiceDeploymentNotifier} notifier implementation.
 * 
 * @author Alexey Stashok
 */
public final class WebServiceDeploymentNotifierImpl implements WebServiceDeploymentNotifier {
    private final Collection<WebServiceDeploymentListener> listeners =
            new CopyOnWriteArraySet<WebServiceDeploymentListener>();

    public void addListener(WebServiceDeploymentListener listener) {
        listeners.add(listener);
    }

    public void removeListener(WebServiceDeploymentListener listener) {
        listeners.remove(listener);
    }

    public void notifyDeployed(WebServiceEndpoint nextEndpoint) {
        for(WebServiceDeploymentListener listener : listeners) {
            listener.onDeployed(nextEndpoint);
        }
    }

    public void notifyUndeployed(WebServiceEndpoint nextEndpoint) {
        for(WebServiceDeploymentListener listener : listeners) {
            listener.onUndeployed(nextEndpoint);
        }
    }

}
