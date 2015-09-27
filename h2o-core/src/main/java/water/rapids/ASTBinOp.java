package water.rapids;

import water.H2O;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;
import water.parser.BufferedString;
import water.util.ArrayUtils;
import water.util.MathUtils;
import java.util.Arrays;

/**
 * Subclasses auto-widen between scalars and Frames, and have exactly two arguments
 */
abstract class ASTBinOp extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"leftArg", "rightArg"}; }
  @Override int nargs() { return 1+2; }
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    Val left = stk.track(asts[1].exec(env));
    Val rite = stk.track(asts[2].exec(env));
    return prim_apply(left,rite);
  }

  Val prim_apply( Val left, Val rite ) {
    switch( left.type() ) {
    case Val.NUM: 
      final double dlf = left.getNum();
      switch( rite.type() ) {
      case Val.NUM:  return new ValNum( op (dlf,rite.getNum()));
      case Val.FRM:  return scalar_op_frame(dlf,rite.getFrame());
      case Val.STR:  throw H2O.unimpl();
      default: throw H2O.fail();
      }

    case Val.FRM: 
      Frame flf = left.getFrame();
      switch( rite.type() ) {
      case Val.NUM:  return frame_op_scalar(flf,rite.getNum());
      case Val.STR:  return frame_op_scalar(flf,rite.getStr());
      case Val.FRM:  return frame_op_frame (flf,rite.getFrame());
      default: throw H2O.fail();
      }
          
    case Val.STR: 
      String slf = left.getStr();
      switch( rite.type() ) {
      case Val.NUM:  throw H2O.unimpl();
      case Val.STR:  throw H2O.unimpl();
      case Val.FRM:  return scalar_op_frame(slf,rite.getFrame());
      default: throw H2O.fail();
      }

    case Val.ROW:
      double dslf[] = left.getRow();
      switch( rite.type() ) {
      case Val.NUM:  throw H2O.unimpl();
      case Val.ROW:  return row_op_row(dslf,rite.getRow());
      default: throw H2O.fail();
      }

    default: throw H2O.fail();
    }
  }

  /** Override to express a basic math primitive */
  abstract double op( double l, double r );
  double str_op( BufferedString l, BufferedString r ) { throw H2O.fail(); }

  /** Auto-widen the scalar to every element of the frame */
  private ValFrame scalar_op_frame( final double d, Frame fr ) {
    Frame res = new MRTask() {
        @Override public void map( Chunk[] chks, NewChunk[] cress ) {
          for( int c=0; c<chks.length; c++ ) {
            Chunk chk = chks[c];
            NewChunk cres = cress[c];
            for( int i=0; i<chk._len; i++ )
              cres.addNum(op(d,chk.atd(i)));
          }
        }
      }.doAll(fr.numCols(),fr).outputFrame(fr._names,null);
    return cleanCategorical(fr, res); // Cleanup categorical misuse
  }

  /** Auto-widen the scalar to every element of the frame */
  ValFrame frame_op_scalar( Frame fr, final double d ) {
    Frame res = new MRTask() {
        @Override public void map( Chunk[] chks, NewChunk[] cress ) {
          for( int c=0; c<chks.length; c++ ) {
            Chunk chk = chks[c];
            NewChunk cres = cress[c];
            for( int i=0; i<chk._len; i++ )
              cres.addNum(op(chk.atd(i),d));
          }
        }
      }.doAll(fr.numCols(),fr).outputFrame(fr._names,null);
    return cleanCategorical(fr, res); // Cleanup categorical misuse
  }

  // Ops do not make sense on categoricals, except EQ/NE; flip such ops to NAs
  private ValFrame cleanCategorical(Frame oldfr, Frame newfr) {
    final boolean categoricalOK = categoricalOK();
    final Vec oldvecs[] = oldfr.vecs();
    final Vec newvecs[] = newfr.vecs();
    for( int i=0; i<oldvecs.length; i++ )
      if( !oldvecs[i].isNumeric() && // Must be numeric OR
          !oldvecs[i].isTime() &&    // time OR
          !(oldvecs[i].isCategorical() && categoricalOK) ) // categorical are OK (op is EQ/NE)
        newvecs[i] = newvecs[i].makeCon(Double.NaN);
    return new ValFrame(newfr);
  }

  /** Auto-widen the scalar to every element of the frame */
  private ValFrame frame_op_scalar( Frame fr, final String str ) {
    Frame res = new MRTask() {
        @Override public void map( Chunk[] chks, NewChunk[] cress ) {
          BufferedString vstr = new BufferedString();
          for( int c=0; c<chks.length; c++ ) {
            Chunk chk = chks[c];
            NewChunk cres = cress[c];
            Vec vec = chk.vec();
            // String Vectors: apply str_op as ValueStrings to all elements
            if( vec.isString() ) {
              final BufferedString conStr = new BufferedString(str);
              for( int i=0; i<chk._len; i++ )
                cres.addNum(str_op(chk.atStr(vstr,i),conStr));
            } else if( vec.isCategorical() ) {
              // categorical Vectors: convert string to domain value; apply op (not
              // str_op).  Not sure what the "right" behavior here is, can
              // easily argue that should instead apply str_op to the categorical
              // string domain value - except that this whole operation only
              // makes sense for EQ/NE, and is much faster when just comparing
              // doubles vs comparing strings.  Note that if the string is not
              // part of the categorical domain, the find op returns -1 which is never
              // equal to any categorical dense integer (which are always 0+).
              final double d = (double)ArrayUtils.find(vec.domain(),str);
              for( int i=0; i<chk._len; i++ )
                cres.addNum(op(chk.atd(i),d));
            } else { // mixing string and numeric
              final double d = op(1,2); // false or true only
              for( int i=0; i<chk._len; i++ )
                cres.addNum(d);
            }
          }
        }
      }.doAll(fr.numCols(),fr).outputFrame(fr._names,null);
    return new ValFrame(res);
  }

  /** Auto-widen the scalar to every element of the frame */
  private ValFrame scalar_op_frame( final String str, Frame fr ) {
    Frame res = new MRTask() {
        @Override public void map( Chunk[] chks, NewChunk[] cress ) {
          BufferedString vstr = new BufferedString();
          for( int c=0; c<chks.length; c++ ) {
            Chunk chk = chks[c];
            NewChunk cres = cress[c];
            Vec vec = chk.vec();
            // String Vectors: apply str_op as ValueStrings to all elements
            if( vec.isString() ) {
              final BufferedString conStr = new BufferedString(str);
              for( int i=0; i<chk._len; i++ )
                cres.addNum(str_op(conStr,chk.atStr(vstr,i)));
            } else if( vec.isCategorical() ) {
              // categorical Vectors: convert string to domain value; apply op (not
              // str_op).  Not sure what the "right" behavior here is, can
              // easily argue that should instead apply str_op to the categorical
              // string domain value - except that this whole operation only
              // makes sense for EQ/NE, and is much faster when just comparing
              // doubles vs comparing strings.
              final double d = (double)ArrayUtils.find(vec.domain(),str);
              for( int i=0; i<chk._len; i++ )
                cres.addNum(op(d,chk.atd(i)));
            } else { // mixing string and numeric
              final double d = op(1,2); // false or true only
              for( int i=0; i<chk._len; i++ )
                cres.addNum(d);
            }
          }
        }
      }.doAll(fr.numCols(),fr).outputFrame(fr._names,null);
    return new ValFrame(res);
  }

  /** Auto-widen: If one frame has only 1 column, auto-widen that 1 column to
   *  the rest.  Otherwise the frames must have the same column count, and
   *  auto-widen element-by-element.  Short-cut if one frame has zero
   *  columns. */
  private ValFrame frame_op_frame( Frame lf, Frame rt ) {
    if( lf.numRows() != rt.numRows() ) 
      throw new IllegalArgumentException("Frames must have same rows, found "+lf.numRows()+" rows and "+rt.numRows()+" rows.");
    if( lf.numCols() == 0 ) return new ValFrame(lf);
    if( rt.numCols() == 0 ) return new ValFrame(rt);
    if( lf.numCols() == 1 && rt.numCols() > 1 ) return vec_op_frame(lf.vecs()[0],rt);
    if( rt.numCols() == 1 && lf.numCols() > 1 ) return frame_op_vec(lf,rt.vecs()[0]);
    if( lf.numCols() != rt.numCols() )
      throw new IllegalArgumentException("Frames must have same columns, found "+lf.numCols()+" columns and "+rt.numCols()+" columns.");

    return new ValFrame(new MRTask() {
        @Override public void map( Chunk[] chks, NewChunk[] cress ) {
          assert (cress.length<<1) == chks.length;
          for( int c=0; c<cress.length; c++ ) {
            Chunk clf = chks[c];
            Chunk crt = chks[c+cress.length];
            NewChunk cres = cress[c];
            for( int i=0; i<clf._len; i++ )
              cres.addNum(op(clf.atd(i),crt.atd(i)));
          }
        }
      }.doAll(lf.numCols(),new Frame(lf).add(rt)).outputFrame(lf._names,null));
  }

  private ValRow row_op_row( double[] lf, double[] rt ) {
    double[] res = new double[lf.length];
    for( int i=0; i<lf.length; i++ )
      res[i] = op(lf[i],rt[i]);
    return new ValRow(res);
  }

  private ValFrame vec_op_frame( Vec vec, Frame fr ) {
    throw H2O.unimpl();
  }
  private ValFrame frame_op_vec( Frame fr, Vec vec ) {
    throw H2O.unimpl();
  }
  
  // Make sense to run this OP on an enm?
  boolean categoricalOK() { return false; }
}

// ----------------------------------------------------------------------------
// Expressions that auto-widen between NUM and FRM
class ASTAnd  extends ASTBinOp { public String str() { return "&" ; } double op( double l, double r ) { return ASTLAnd.and_op(l,r); } }
class ASTDiv  extends ASTBinOp { public String str() { return "/" ; } double op( double l, double r ) { return l/r;}}
class ASTMod  extends ASTBinOp { public String str() { return "mod";} double op( double l, double r ) { return l%r;}}
class ASTMul  extends ASTBinOp { public String str() { return "*" ; } double op( double l, double r ) { return l*r;}}
class ASTOr   extends ASTBinOp { public String str() { return "|" ; } double op( double l, double r ) { return ASTLOr . or_op(l,r); } }
class ASTPlus extends ASTBinOp { public String str() { return "+" ; } double op( double l, double r ) { return l+ r; } }
class ASTPow  extends ASTBinOp { public String str() { return "^" ; } double op( double l, double r ) { return Math.pow(l,r); } }
class ASTSub  extends ASTBinOp { public String str() { return "-" ; } double op( double l, double r ) { return l- r; } }
class ASTIntDiv extends ASTBinOp { public String str() { return "intDiv"; } double op(double l, double r) { return (int)l/(int)r;}}

class ASTRound extends ASTBinOp { 
  public String str() { return "round"; }
  double op(double x, double digits) { 
    // e.g.: floor(2.676*100 + 0.5) / 100 => 2.68
    if(Double.isNaN(x)) return x;
    double sgn = x < 0 ? -1 : 1;
    x = Math.abs(x);
    double power_of_10 = (int)Math.pow(10, (int)digits);
    return sgn*(digits == 0
                // go to the even digit
                ? (x % 1 >= 0.5 && !(Math.floor(x)%2==0))
                ? Math.ceil(x)
                : Math.floor(x)
                : Math.floor(x * power_of_10 + 0.5) / power_of_10);
  }
}

class ASTSignif extends ASTBinOp { 
  public String str() { return "signif"; }
  double op(double x, double digits) { 
    if(Double.isNaN(x)) return x;
    java.math.BigDecimal bd = new java.math.BigDecimal(x);
    bd = bd.round(new java.math.MathContext((int)digits, java.math.RoundingMode.HALF_EVEN));
    return bd.doubleValue();
  }
}


class ASTGE   extends ASTBinOp { public String str() { return ">="; } double op( double l, double r ) { return l>=r?1:0; } }
class ASTGT   extends ASTBinOp { public String str() { return ">" ; } double op( double l, double r ) { return l> r?1:0; } }
class ASTLE   extends ASTBinOp { public String str() { return "<="; } double op( double l, double r ) { return l<=r?1:0; } }
class ASTLT   extends ASTBinOp { public String str() { return "<" ; } double op( double l, double r ) { return l< r?1:0; } }

class ASTEQ   extends ASTBinOp { public String str() { return "=="; } double op( double l, double r ) { return MathUtils.equalsWithinOneSmallUlp(l,r)?1:0; }
  double str_op( BufferedString l, BufferedString r ) { return l==null ? (r==null?1:0) : (l.equals(r) ? 1 : 0); }
  @Override ValFrame frame_op_scalar( Frame fr, final double d ) {
    return new ValFrame(new MRTask() {
        @Override public void map( Chunk[] chks, NewChunk[] cress ) {
          for( int c=0; c<chks.length; c++ ) {
            Chunk chk = chks[c];
            NewChunk cres = cress[c];
            if( !chk.vec().isNumeric() ) cres.addZeros(chk._len);
            else 
              for( int i=0; i<chk._len; i++ )
                cres.addNum(op(chk.atd(i),d));
          }
        }
      }.doAll(fr.numCols(),fr).outputFrame());
  }
  @Override boolean categoricalOK() { return true; }  // Make sense to run this OP on an enm?
}

class ASTNE   extends ASTBinOp { public String str() { return "!="; } double op( double l, double r ) { return MathUtils.equalsWithinOneSmallUlp(l,r)?0:1; }
  double str_op( BufferedString l, BufferedString r ) { return l==null ? (r==null?0:1) : (l.equals(r) ? 0 : 1); }
  @Override boolean categoricalOK() { return true; }  // Make sense to run this OP on an enm?
}

// ----------------------------------------------------------------------------
// Logical-AND.  If the first arg is false, do not execute the 2nd arg.
class ASTLAnd extends ASTBinOp { 
  public String str() { return "&&"; }
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    Val left = stk.track(asts[1].exec(env));
    // If the left is zero or NA, do not evaluate the right, just return the left
    if( left.isNum() ) {
      double d = ((ValNum)left)._d;
      if( d==0 || Double.isNaN(d) ) return left;
    }
    Val rite = stk.track(asts[2].exec(env));
    return prim_apply(left,rite);
  }
  // Weird R semantics, zero trumps NA
  double op( double l, double r ) { return and_op(l,r); }
  static double and_op( double l, double r ) {   
    return (l==0||r==0) ? 0 : (Double.isNaN(l) || Double.isNaN(r) ? Double.NaN : 1); 
  } 
}

// Logical-OR.  If the first arg is true, do not execute the 2nd arg.
class ASTLOr extends ASTBinOp { 
  public String str() { return "||"; }
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    Val left = stk.track(asts[1].exec(env));
    // If the left is non-zero or NA, do not evaluate the right, just return the left
    if( left.isNum() ) {
      double d = ((ValNum)left)._d;
      if( d!=0 || Double.isNaN(d) ) return left;
    }
    Val rite = stk.track(asts[2].exec(env));
    return prim_apply(left,rite);
  }
  // Weird R semantics, zero trumps NA
  double op( double l, double r ) { return or_op(l,r); }
  static double or_op( double l, double r ) {   
    return (l!=0||r!=0) ? 1 : (Double.isNaN(l) || Double.isNaN(r) ? Double.NaN : 0); 
  } 
}

// IfElse.  
//
// "NaNs poison".  If the test is a NaN, evaluate neither side and return a NaN
//
// "Frames poison".  If the test is a Frame, both sides are evaluated and
// selected between according to the test.  The result is a Frame.  All Frames
// must be compatible, and scalars and 1-column Frames are widened to match the
// widest frame.  NaN test values produce NaN results.
//
// If the test is a scalar, then only the returned side is evaluated.  If both
// sides are scalars or frames, then the evaluated result is returned.  The
// unevaluated side is not checked for being a compatible frame.  It is an
// error if one side is typed as a scalar and the other as a Frame.
//
class ASTIfElse extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"test","true","false"}; }
  @Override int nargs() { return 1+3; } // (ifelse test true false)
  public String str() { return "ifelse"; }
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    Val val = stk.track(asts[1].exec(env));

    if( val.isNum() ) {         // Scalar test, scalar result
      double d = val.getNum();
      if( Double.isNaN(d) ) return new ValNum(Double.NaN);
      Val res = stk.track(asts[d==0 ? 3 : 2].exec(env)); // exec only 1 of false and true
      return res.isFrame() ? new ValNum(res.getFrame().vec(0).at(0)) : res;
    }

    // Frame test.  Frame result.
    Frame tst = val.getFrame();

    // If all zero's, return false and never execute true.
    Frame fr = new Frame(tst);
    Val tval = null;
    for( Vec vec : tst.vecs() )
      if( vec.min()!=0 || vec.max()!= 0 ) {
        tval = exec_check(env,stk,tst,asts[2],fr);
        break;
      }
    final boolean has_tfr = tval != null && tval.isFrame();
    final double td = (tval != null && tval.isNum()) ? tval.getNum() : Double.NaN;

    // If all nonzero's (or NA's), then never execute false.
    Val fval = null;
    for( Vec vec : tst.vecs() )
      if( vec.nzCnt()+vec.naCnt() < vec.length() ) {
        fval = exec_check(env,stk,tst,asts[3],fr);
        break;
      }
    final boolean has_ffr = fval != null && fval.isFrame();
    final double fd = (fval != null && fval.isNum()) ? fval.getNum() : Double.NaN;

    // Now pick from left-or-right in the new frame
    Frame res = new MRTask() {
        @Override public void map( Chunk chks[], NewChunk nchks[] ) {
          assert nchks.length+(has_tfr ? nchks.length : 0)+(has_ffr ? nchks.length : 0) == chks.length;
          for( int i=0; i<nchks.length; i++ ) {
            Chunk ctst = chks[i];
            NewChunk res = nchks[i];
            for( int row=0; row<ctst._len; row++ ) {
              double d;
              if(     ctst.isNA(row)    ) d = Double.NaN;
              else if( ctst.atd(row)==0 ) d = has_ffr ? chks[i+nchks.length+(has_tfr ? nchks.length : 0)].atd(row) : fd;
              else                        d = has_tfr ? chks[i+nchks.length                             ].atd(row) : td;
              res.addNum(d);
            }
          }
        }
      }.doAll(tst.numCols(),fr).outputFrame();
    return new ValFrame(res);
  }

  Val exec_check( Env env, Env.StackHelp stk, Frame tst, AST ast, Frame xfr ) {
    Val val = ast.exec(env);
    if( val.isFrame() ) {
      Frame fr = stk.track(val).getFrame();
      if( tst.numCols() != fr.numCols() || tst.numRows() != fr.numRows() )
        throw new IllegalArgumentException("ifelse test frame and other frames must match dimensions, found "+tst+" and "+fr);
      xfr.add(fr);
    }
    return val;
  }
}

// Center and scale a frame.  Can be passed in the centers and scales (one per
// column in an number list), or a TRUE/FALSE.
class ASTScale extends ASTPrim {
  @Override
  public String[] args() { return new String[]{"ary", "center", "scale"}; }
  @Override int nargs() { return 1+3; } // (scale x center scale)
  @Override
  public String str() { return "scale"; }
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    int ncols = fr.numCols();

    // Peel out the bias/shift/mean
    double[] means;
    if( asts[2] instanceof ASTNumList ) {
      means = ((ASTNumList)asts[2]).expand();
      if( means.length != ncols )
        throw new IllegalArgumentException("Numlist must be the same length as the columns of the Frame");
    } else {
      double d = asts[2].exec(env).getNum();
      if( d==0 )      means = new double[ncols]; // No change on means, so zero-filled
      else if( d==1 ) means = fr.means();
      else throw new IllegalArgumentException("Only true or false allowed");
    }

    // Peel out the scale/stddev
    double[] mults;
    if( asts[3] instanceof ASTNumList ) {
      mults = ((ASTNumList)asts[3]).expand();
      if( mults.length != ncols )
        throw new IllegalArgumentException("Numlist must be the same length as the columns of the Frame");
    } else {
      Val v = asts[3].exec(env);
      if( v instanceof ValFrame ) {
        mults = toArray(v.getFrame().anyVec());
      } else {
        double d = v.getNum();
        if (d == 0)
          Arrays.fill(mults = new double[ncols], 1.0); // No change on mults, so one-filled
        else if (d == 1) mults = fr.mults();
        else throw new IllegalArgumentException("Only true or false allowed");
      }
    }

    // Update in-place.
    final double[] fmeans = means; // Make final copy for closure
    final double[] fmults = mults; // Make final copy for closure
    new MRTask() {
      @Override public void map( Chunk[] cs ) {
        for( int i=0; i<cs.length; i++ )
          for( int row=0; row<cs[i]._len; row++ )
            cs[i].set(row,(cs[i].atd(row)-fmeans[i])*fmults[i]);
      }
    }.doAll(fr);
    return new ValFrame(fr);
  }

  private static double[] toArray(Vec v) {
    double[] res = new double[(int)v.length()];
    for(int i=0;i<res.length;++i)
      res[i] = v.at(i);
    return res;
  }
}

