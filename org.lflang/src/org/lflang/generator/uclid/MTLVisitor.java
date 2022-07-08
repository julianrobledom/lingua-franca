/*************
Copyright (c) 2021, The University of California at Berkeley.

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

/** 
 * Transpiler from an MTL specification to a Uclid axiom.
 * 
 * @author{Shaokai Lin <shaokai@berkeley.edu>}
 */
package org.lflang.generator.uclid;

import org.lflang.TimeUnit;
import org.lflang.TimeValue;
import org.lflang.dsl.MTLParser;
import org.lflang.dsl.MTLParserBaseVisitor;
import org.lflang.generator.CodeBuilder;

public class MTLVisitor extends MTLParserBaseVisitor<String> {

    ////////////////////////////////////////////
    //// Protected fields

    /** The main place to put generated code. */
    protected CodeBuilder code  = new CodeBuilder();

    /** Tactic to be used to prove the property. */
    protected String tactic;

    // Constructor
    public MTLVisitor(String tactic) {
        this.tactic = tactic;
    }

    ////////////////////////////////////////////
    //// Public methods
    public String visitMtl(MTLParser.MtlContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        return visitEquivalence(ctx.equivalence(),
            QFPrefix, QFIdx, prevQFIdx, horizon);
    }

    public String visitEquivalence(MTLParser.EquivalenceContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        if (ctx.right == null) {
            return visitImplication(ctx.left,
            QFPrefix, QFIdx, prevQFIdx, horizon);
        }
        return "(" + visitImplication(ctx.left,
            QFPrefix, QFIdx, prevQFIdx, horizon) 
            + ")"
            + " <==> " 
            + "(" + visitImplication(ctx.right,
            QFPrefix, QFIdx, prevQFIdx, horizon)
            + ")";
    }

    public String visitImplication(MTLParser.ImplicationContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {
        
        if (ctx.right == null) {
            return visitDisjunction(ctx.left,
            QFPrefix, QFIdx, prevQFIdx, horizon);
        }
        return "(" + visitDisjunction(ctx.left,
            QFPrefix, QFIdx, prevQFIdx, horizon) 
            + ")"
            + " ==> " 
            + "(" + visitDisjunction(ctx.right,
            QFPrefix, QFIdx, prevQFIdx, horizon)
            + ")";
    }

    public String visitDisjunction(MTLParser.DisjunctionContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {
        
        String str = "";
        for (int i = 0; i < ctx.terms.size(); i++) {
            str += "("
                + visitConjunction(ctx.terms.get(i),
                QFPrefix, QFIdx, prevQFIdx, horizon)
                + ")"
                + (i == ctx.terms.size()-1 ? "" : "||");
        }
        return str;
    }

    public String visitConjunction(MTLParser.ConjunctionContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {
        
        String str = "";
        for (int i = 0; i < ctx.terms.size(); i++) {
            str += "("
                + visitUntil((MTLParser.UntilContext)ctx.terms.get(i),
                    QFPrefix, QFIdx, prevQFIdx, horizon)
                + ")"
                + (i == ctx.terms.size()-1 ? "" : "&&");
        }
        return str;
    }

    // A custom dispatch function
    public String _visitUnaryOp(MTLParser.UnaryOpContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        // FIXME: Is there a more "antlr" way to do dispatch here?
        if (ctx instanceof MTLParser.NoUnaryOpContext) {
            return visitNoUnaryOp((MTLParser.NoUnaryOpContext)ctx, 
                QFPrefix, QFIdx, prevQFIdx, horizon);
        }
        if (ctx instanceof MTLParser.NegationContext) {
            return visitNegation((MTLParser.NegationContext)ctx,
                QFPrefix, QFIdx, prevQFIdx, horizon);
        }
        if (ctx instanceof MTLParser.NextContext) {
            return visitNext((MTLParser.NextContext)ctx,
                QFPrefix, QFIdx, prevQFIdx, horizon);
        }
        if (ctx instanceof MTLParser.GloballyContext) {
            return visitGlobally((MTLParser.GloballyContext)ctx,
                QFPrefix, QFIdx, prevQFIdx, horizon);
        }
        if (ctx instanceof MTLParser.FinallyContext) {
            return visitFinally((MTLParser.FinallyContext)ctx,
                QFPrefix, QFIdx, prevQFIdx, horizon);
        }

        // FIXME: Throw an exception.
        return "";
    }

    public String visitUntil(MTLParser.UntilContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        // If right is null, continue recursion.
        if (ctx.right == null) {
            return _visitUnaryOp(ctx.left,
                QFPrefix, QFIdx, prevQFIdx, horizon);
        }

        String end;
        if (this.tactic.equals("induction")) {
            end = "(" + QFPrefix + " + N)";
        } else {
            end = "END";
        }

        // Otherwise, create the Until formula.
        // Check if the time interval is a range or a singleton.
        if (ctx.timeInterval instanceof MTLParser.SingletonContext) {
            MTLParser.SingletonContext singletonCtx = (MTLParser.SingletonContext)ctx.timeInterval;
            String timeInstantValue = singletonCtx.instant.value.getText();
            String timeInstantUnit = "";
            long timeInstantNanoSec = 0;
            if (!timeInstantValue.equals("0")) {
                timeInstantUnit = singletonCtx.instant.unit.getText();
                TimeValue timeValue = new TimeValue(
                    Integer.valueOf(timeInstantValue), 
                    TimeUnit.fromName(timeInstantUnit));
                timeInstantNanoSec = timeValue.toNanoSeconds();
            }

            String timePredicate = "tag_same(g(" + QFPrefix + "), "
                + "tag_schedule(g(" + prevQFIdx + "), nsec(" + timeInstantNanoSec + ")))";
            long currentHorizon = horizon + timeInstantNanoSec;

            return "finite_exists " + "(" + "j" + QFIdx + " : integer) in indices :: "
                + "j" + QFIdx + " >= " + QFPrefix + " && " + "j" + QFIdx + " <= " + end
                + " && " + "(" + _visitUnaryOp(ctx.right, ("j"+QFIdx), QFIdx+1, QFPrefix, currentHorizon) + ")"
                + " && " + "(" + "\n" 
                + "// Time Predicate\n"
                + timePredicate + "\n"
                + ")" + " && " + "(" + "finite_forall " + "(" + "i" + QFIdx + " : integer) in indices :: "
                + "(" + "i" + QFIdx + " >= " + QFPrefix + " && " + "i" + QFIdx + " < " + "j" + QFIdx + ")"
                + " ==> " + "(" + _visitUnaryOp(ctx.left, ("i"+QFIdx), QFIdx+1, ("j"+QFIdx), currentHorizon) + ")" + ")";
        } 
        else {
            MTLParser.RangeContext rangeCtx = (MTLParser.RangeContext)ctx.timeInterval;
            String lowerBoundTimeValue = rangeCtx.lowerbound.value.getText();
            String lowerBoundTimeUnit = "";
            long lowerBoundNanoSec = 0;
            if (!lowerBoundTimeValue.equals("0")) {
                lowerBoundTimeUnit = rangeCtx.lowerbound.unit.getText();
                TimeValue lowerTimeValue = new TimeValue(
                    Integer.valueOf(lowerBoundTimeValue), 
                    TimeUnit.fromName(lowerBoundTimeUnit));
                lowerBoundNanoSec = lowerTimeValue.toNanoSeconds();
            }
            
            String upperBoundTimeValue = rangeCtx.upperbound.value.getText();
            String upperBoundTimeUnit = "";
            long upperBoundNanoSec = 0;
            if (!upperBoundTimeValue.equals("0")) {
                upperBoundTimeUnit = rangeCtx.upperbound.unit.getText();
                TimeValue upperTimeValue = new TimeValue(
                    Integer.valueOf(upperBoundTimeValue), 
                    TimeUnit.fromName(upperBoundTimeUnit));
                upperBoundNanoSec = upperTimeValue.toNanoSeconds();
            }

            String timePredicate = "";
            timePredicate += "(";
            if (rangeCtx.LPAREN() != null) {
                timePredicate += "tag_later(g(" + QFPrefix + "), "
                    + "tag_schedule(g(" + prevQFIdx + "), nsec(" + lowerBoundNanoSec + ")))";
            } else {
                // FIXME: Check if this can be replaced by a !tag_earlier.
                timePredicate += "tag_later(g(" + QFPrefix + "), "
                    + "tag_schedule(g(" + prevQFIdx + "), nsec(" + lowerBoundNanoSec + ")))"
                    + " || " + "tag_same(g(" + QFPrefix + "), "
                    + "tag_schedule(g(" + prevQFIdx + "), nsec(" + lowerBoundNanoSec + ")))";
            }
            timePredicate += ") && (";
            if (rangeCtx.RPAREN() != null) {
                timePredicate += "tag_earlier(g(" + QFPrefix + "), "
                    + "tag_schedule(g(" + prevQFIdx + "), nsec(" + upperBoundNanoSec + ")))";
            } else {
                timePredicate += "tag_earlier(g(" + QFPrefix + "), "
                    + "tag_schedule(g(" + prevQFIdx + "), nsec(" + upperBoundNanoSec + ")))"
                    + " || " + "tag_same(g(" + QFPrefix + "), "
                    + "tag_schedule(g(" + prevQFIdx + "), nsec(" + upperBoundNanoSec + ")))";
            }
            timePredicate += ")";
                        
            long currentHorizon = horizon + upperBoundNanoSec;

            return "finite_exists " + "(" + "j" + QFIdx + " : integer) in indices :: "
                + "j" + QFIdx + " >= " + QFPrefix + " && " + "j" + QFIdx + " <= " + end
                + " && " + "(" + _visitUnaryOp(ctx.right, ("j"+QFIdx), QFIdx+1, QFPrefix, currentHorizon) + ")"
                + " && " + "(" + "\n" 
                + "// Time Predicate\n"
                + timePredicate + "\n"
                + ")" + " && " + "(" + "finite_forall " + "(" + "i" + QFIdx + " : integer) in indices :: "
                + "(" + "i" + QFIdx + " >= " + QFPrefix + " && " + "i" + QFIdx + " < " + "j" + QFIdx + ")"
                + " ==> " + "(" + _visitUnaryOp(ctx.left, ("i"+QFIdx), QFIdx+1, ("j"+QFIdx), currentHorizon) + ")" + ")";
        }
    }

    public String visitNoUnaryOp(MTLParser.NoUnaryOpContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        return "";
    }

    public String visitNegation(MTLParser.NegationContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        return "";
    }

    public String visitNext(MTLParser.NextContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        return "";
    }

    public String visitGlobally(MTLParser.GloballyContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        return "";
    }

    public String visitFinally(MTLParser.FinallyContext ctx,
        String QFPrefix, int QFIdx, String prevQFIdx, long horizon) {

        return "";
    }
}