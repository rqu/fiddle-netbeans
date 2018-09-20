/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.html.ojet.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.event.ChangeListener;
import org.netbeans.api.project.Project;
import org.netbeans.modules.html.ojet.data.DataProviderImpl;
import org.netbeans.modules.html.ojet.data.OJETPreferences;
import org.netbeans.modules.web.clientproject.spi.CustomizerPanelImplementation;
import org.netbeans.spi.project.ui.support.ProjectCustomizer;
import org.openide.util.ChangeSupport;
import org.openide.util.HelpCtx;
import org.openide.util.NbBundle;

/**
 *
 * @author Petr Pisl
 */
public class OJETPanel extends javax.swing.JPanel implements CustomizerPanelImplementation, HelpCtx.Provider {

    public static final String IDENTIFIER = "ojet"; // NOI18N
    
    private final ChangeSupport changeSupport = new ChangeSupport(this);
    private final Project project;
    private final ProjectCustomizer.Category category;
    /**
     * Creates new form OJETPanel
     */
    public OJETPanel(Project project, ProjectCustomizer.Category category) {
        this.project = project;
        this.category = category;
        initComponents();
        initData();
    }

    private void initData() {
        DefaultComboBoxModel model = new DefaultComboBoxModel();
        for (String version: DataProviderImpl.getInstance().getAvailableVersions()) {
            model.addElement(version);
        }
        model.setSelectedItem(OJETPreferences.get(project, OJETPreferences.VERSION));
        cbVersion.setModel(model);
        category.setStoreListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
            }
        } );
    }
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        cbVersion = new javax.swing.JComboBox();
        lVersion = new javax.swing.JLabel();

        cbVersion.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
        cbVersion.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                cbVersionItemStateChanged(evt);
            }
        });

        org.openide.awt.Mnemonics.setLocalizedText(lVersion, org.openide.util.NbBundle.getMessage(OJETPanel.class, "OJETPanel.lVersion.text")); // NOI18N

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(lVersion)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(cbVersion, 0, 265, Short.MAX_VALUE)
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cbVersion, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lVersion))
                .addContainerGap(269, Short.MAX_VALUE))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void cbVersionItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_cbVersionItemStateChanged
        changeSupport.fireChange();
    }//GEN-LAST:event_cbVersionItemStateChanged


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JComboBox cbVersion;
    private javax.swing.JLabel lVersion;
    // End of variables declaration//GEN-END:variables

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    @NbBundle.Messages("OJETPanel.name=Oracle JET")
    @Override
    public String getDisplayName() {
        return Bundle.OJETPanel_name();
    }

    @Override
    public void addChangeListener(ChangeListener listener) {
        changeSupport.addChangeListener(listener);
    }

    @Override
    public void removeChangeListener(ChangeListener listener) {
        changeSupport.removeChangeListener(listener);
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public String getWarningMessage() {
        return null;
    }

    @Override
    public void save() {
        DataProviderImpl.getInstance().setCurrentVersion((String)cbVersion.getSelectedItem());
        OJETPreferences.put(project, OJETPreferences.VERSION, (String)cbVersion.getSelectedItem());
    }

    @Override
    public HelpCtx getHelpCtx() {
        return new HelpCtx("org.netbeans.modules.html.ojet.ui.OJETPanel"); // NOI18N
    }
}