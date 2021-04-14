/* Custom resource description strategy for Lingua Franca. */

/*************
Copyright (c) 2019, The University of California at Berkeley.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
***************/

package org.lflang

import com.google.inject.Inject
import java.util.HashMap
import org.eclipse.xtext.naming.QualifiedName
import org.eclipse.xtext.resource.EObjectDescription
import org.eclipse.xtext.resource.IEObjectDescription
import org.eclipse.xtext.resource.impl.DefaultResourceDescriptionStrategy
import org.eclipse.xtext.scoping.impl.ImportUriResolver
import org.eclipse.xtext.util.IAcceptor
import org.eclipse.emf.ecore.EObject
import org.lflang.meta.Model

/**
 * Resource description strategy designed to limit global scope to only those
 * files that were explicitly imported.
 * 
 * Adapted from example provided by Itemis.
 * @see https://blogs.itemis.com/en/in-five-minutes-to-transitive-imports-within-a-dsl-with-xtext
 * @author{Marten Lohstroh <marten@berkeley.edu>}
 */
class LinguaFrancaResourceDescriptionStrategy extends DefaultResourceDescriptionStrategy {
    
    /**
     * Key used in user data attached to description of a Model.
     */
    public static final String INCLUDES = "includes"
    
    /**
     * Delimiter used in the values associated with INCLUDES keys in the
     * user-data descriptions of Models.
     */
    public static final String DELIMITER = ','

    @Inject
    ImportUriResolver uriResolver

    /**
     * Handle eObject instances of type "Model" separately.
     */
    override createEObjectDescriptions(EObject eObject,
        IAcceptor<IEObjectDescription> acceptor) {
        if (eObject instanceof Model) {
            this.createEObjectDescriptionForModel(eObject, acceptor)
            return true
        } else {
            super.createEObjectDescriptions(eObject, acceptor)
        }
    }

    /**
     * Build an index containing the strings of the URIs imported resources.
     * 
     * All the URIs are added to comma-separated string and stored under the 
     * key "includes" in the userData map of the object description.
     **/
    def void createEObjectDescriptionForModel(Model model,
        IAcceptor<IEObjectDescription> acceptor) {
        val uris = newLinkedHashSet()
        model.imports.forEach[uris.add(uriResolver.apply(it))]
        val userData = new HashMap<String, String>
        userData.put(INCLUDES, uris.join(DELIMITER))
        acceptor.accept(
            EObjectDescription.create(
                QualifiedName.create(model.eResource.URI.toString), model,
                userData))
    }
}
