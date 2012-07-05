package neu.sxc.expression.syntax;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import neu.sxc.expression.lexical.LexicalConstants;
import neu.sxc.expression.syntax.function.FunctionRunner;
import neu.sxc.expression.syntax.operator.AssignOperator;
import neu.sxc.expression.syntax.operator.Operator;
import neu.sxc.expression.tokens.ConstToken;
import neu.sxc.expression.tokens.Control;
import neu.sxc.expression.tokens.ControlToken;
import neu.sxc.expression.tokens.DataType;
import neu.sxc.expression.tokens.DelimiterToken;
import neu.sxc.expression.tokens.ExecutionToken;
import neu.sxc.expression.tokens.FunctionToken;
import neu.sxc.expression.tokens.NonterminalToken;
import neu.sxc.expression.tokens.TerminalToken;
import neu.sxc.expression.tokens.Token;
import neu.sxc.expression.tokens.TokenType;
import neu.sxc.expression.tokens.Valuable;
import neu.sxc.expression.tokens.VariableToken;
import neu.sxc.expression.utils.Stack;

/**
 * 语法分析
 * @author 单学成
 *
 */
public class SyntaxAnalyzer {
	private Grammar grammar = Grammar.getGrammar();
	
	/**
	 * 最终结果
	 */
	private Valuable finalResult;
	
	/**
	 * 语法栈
	 */
	private Stack<Token> syntaxStack = new Stack<Token>();
	
	private Stack<Valuable> semanticStack = new Stack<Valuable>();
	
	private Stack<DelimiterToken> operatorTokenStack = new Stack<DelimiterToken>();
	
	private Stack<FunctionToken> functionTokenStack = new Stack<FunctionToken>();
	
	/**
	 * 用于记录函数参数在Token序列中的开始位置
	 */
	private Stack<Integer> argumentStartIndexStack = new Stack<Integer>();
	
	/**
	 * 上下文栈
	 */
	private Stack<Context> contextStack = new Stack<Context>();
	
	/**
	 * 用于压入if-else语句各分支的条件的栈
	 */
	private Stack<Boolean> conditionStack = new Stack<Boolean>();
	
	public SyntaxAnalyzer() {}
	
	public Valuable getFinalResult() {
		return finalResult;
	}
	
	public Map<String, Valuable> getVariableTable() {
		if(contextStack.isEmpty())
			return null;
		return contextStack.top().getVariableTable();
	}
	
	public Valuable analysis(List<TerminalToken> tokens) throws SyntaxException {
		return analysis(tokens, null);
	}
	
	public Valuable analysis(List<TerminalToken> tokens, Map<String, Valuable> variableTable)
				throws SyntaxException {
		this.finalResult = null;
		Map<String, Valuable> initVariableTable = variableTable == null ? 
									new HashMap<String, Valuable>() : variableTable;
		contextStack.push(new Context(true, initVariableTable, 0));
		
		int index = 0;
		while(index < tokens.size()) {
			index = analysisSentence(tokens, index);
		}
		
		return finalResult;
	}
	
	/**
	 * 解析一条语句
	 * @param tokens
	 * @param index
	 * @return
	 * @throws SyntaxException
	 */
	private int analysisSentence(List<TerminalToken> tokens, int index)
				throws SyntaxException {
		prepareStacks();
		syntaxStack.push(grammar.getStart());
		TerminalToken currentToken = tokens.get(index++);
		Token syntaxStackTop = null;
		while(!syntaxStack.isEmpty()) {//栈空时一条语句分析结束
			syntaxStackTop = syntaxStack.pop();
			switch(syntaxStackTop.getTokenType()) {
			case NT:
				Token[] production = ((NonterminalToken)syntaxStackTop).getProduction(currentToken);
				if(production != null)
					reverseProductionIntoSyntaxStack(production);
				else 
					throw new SyntaxException(currentToken);
				break;
			case EXECUTION:
				ExecutionToken executionToken = (ExecutionToken)syntaxStackTop;
				Executable executable = executionToken.getExecutable();
				if(executable instanceof Operator) {
					//执行操作符
					executeOperator((Operator)executable);
				} else {
					//执行函数
					executeFunction((FunctionRunner)executable);
				}
				break;
			case CONTROLLER:
				ControlToken controlToken = (ControlToken)syntaxStackTop;
				try {
					controlExcution(controlToken.getControl());
				} catch (SyntaxException e) {
					throw new SyntaxException(e.getMessage(), currentToken, e);
				}
				break;
			default:
				if(matchTerminalToken((TerminalToken)syntaxStackTop, currentToken)
						&& !syntaxStack.isEmpty()) {
					if(index < tokens.size())
						currentToken = tokens.get(index++);
					else 
						throw new SyntaxException("Sentence is not properly over at line:"
								+ currentToken.getLine() + ".");
				}
				break;
			}
		}
		
		if(!semanticStack.isEmpty())
			finalResult = semanticStack.pop();
		
		return index;
	}
	
	/**
	 * 判断终结符是否匹配
	 * @param syntaxStackTop
	 * @param currentToken
	 * @return
	 * @throws SyntaxException
	 */
	private boolean matchTerminalToken(TerminalToken syntaxStackTop, TerminalToken currentToken)
							throws SyntaxException {
		boolean currentTokenMatched = syntaxStackTop.equalsInGrammar(currentToken);
		if(currentTokenMatched) {
			switch(syntaxStackTop.getTokenType()) {
			case CONST:
				semanticStack.push((ConstToken)currentToken);
				break;
			case VARIABLE:
				VariableToken variable = (VariableToken)currentToken;
				Valuable valueOfVariable = getVariableValue(variable.getText());
				if(valueOfVariable != null)
					variable.assignWith(valueOfVariable);
				semanticStack.push(variable);
				break;
			case DELIMITER:
				if(LexicalConstants.OPERATORS.contains(currentToken.getText()))
					operatorTokenStack.push((DelimiterToken)currentToken);
				break;
			case FUNCTION:
				functionTokenStack.push((FunctionToken)currentToken);
				argumentStartIndexStack.push(semanticStack.size());
				break;
			case KEY:
				break;
			}
		} else {
			throw new SyntaxException(currentToken);
		}
		return currentTokenMatched;
	}
	
	/**
	 * 将产生式反序压入语法栈
	 * @param production
	 */
	private void reverseProductionIntoSyntaxStack(Token[] production) {
		if(production.length > 0)
			for(int i=production.length-1; i>=0; i--)
				syntaxStack.push(production[i]);
	}
	
	/**
	 * 执行操作符
	 * @param operator
	 * @throws VariableNotInitializedException
	 * @throws ArgumentsMismatchException
	 */
	private void executeOperator(Operator operator) 
				throws VariableNotInitializedException, ArgumentsMismatchException {
		Valuable[] arguments = getArgumentsForOperator(operator);
		DelimiterToken operatorToken = operatorTokenStack.pop();
		try {
			Valuable result = operator.execute(arguments);
			if(operator instanceof AssignOperator){
				VariableToken variable = (VariableToken)arguments[0];
				setVariableValue(variable.getText(), result);
			}
			semanticStack.push(result);
		} catch(ArgumentsMismatchException e) {
			throw new ArgumentsMismatchException(e.getMessage(), operatorToken, e);
		} catch(ArithmeticException e) {
			ArithmeticException arithmeticException = new ArithmeticException(e.getMessage()
					+ " At line:" + operatorToken.getLine() 
					+ ", column:" + operatorToken.getColumn() + ".");
			arithmeticException.initCause(e);
			throw arithmeticException;
		}
	}
	
	/**
	 * 执行函数
	 * @param functionRunner
	 * @throws VariableNotInitializedException
	 * @throws ArgumentsMismatchException
	 */
	private void executeFunction(FunctionRunner functionRunner) 
				throws VariableNotInitializedException, ArgumentsMismatchException {
		FunctionToken functionToken = functionTokenStack.pop();
		functionRunner.setFunction(functionToken.getFunction());
		int argumentNum = semanticStack.size() - argumentStartIndexStack.pop();
		Valuable[] arguments = getArgumentsForFunction(argumentNum);
		try {
			Valuable result = functionRunner.execute(arguments);
			semanticStack.push(result);
		} catch(ArgumentsMismatchException e) {
			throw new ArgumentsMismatchException(e.getMessage(), functionToken, e);
		}
	}
	
	private Valuable[] getArgumentsForOperator(Operator operator) 
					throws VariableNotInitializedException {
		return getArguments(operator.getArgumentNum(), operator instanceof AssignOperator);
	}
	
	private Valuable[] getArgumentsForFunction(int argumentNum) 
					throws VariableNotInitializedException {
		return getArguments(argumentNum, false);
	}
	
	private Valuable[] getArguments(int argumentNum, boolean isForAssignment)
					throws VariableNotInitializedException {
		Valuable[] arguments = new Valuable[argumentNum];
		for(int i=argumentNum-1; i>=0; i--) {
			arguments[i] = semanticStack.pop();
			if(arguments[i].getTokenType() == TokenType.VARIABLE) {
				if(isForAssignment && i == 0)
					break;
				else if(arguments[i].getIndex() < 0) 
					throw new VariableNotInitializedException((VariableToken)arguments[i]);
			}
		}
		return arguments;
	}
	
	private void controlExcution(Control control) throws SyntaxException {
		switch(control) {
		case IF_CONDITION:
			Valuable condition = semanticStack.pop();
			if(condition.getDataType() != DataType.BOOLEAN)
				throw new SyntaxException("Type mismatch: cannot convert from " +
						condition.getDataType().name() + " to BOOLEAN.");
			else
				conditionStack.push(condition.getBooleanValue());
			break;
		case ELSE_CONDITION:
			conditionStack.push(!conditionStack.pop());
			break;
		case END_IF:
			conditionStack.pop();
			break;
		case NEW_CONTEXT:
			boolean effective = conditionStack.top();
			Context currentContext = contextStack.top();
			contextStack.push(currentContext.constructUpon(effective, semanticStack.size()));
			break;
		case END_CONTEXT:
			Context topContext = contextStack.pop();
			if(topContext.isEffective()) {
				contextStack.top().update(topContext);
			} else {
				recoverSemanticStack(topContext.getStartIndex());
			}
			break;
		}
	}
	
	private Valuable getVariableValue(String variableName) {
		Context currentContext = contextStack.top();
		return currentContext.getVariableValue(variableName);
	}
	
	private void setVariableValue(String text, Valuable value) {
		Context currentContext = contextStack.top();
		currentContext.setVariableValue(text, value);
	}
	
	private void recoverSemanticStack(int startIndex) {
		while(semanticStack.size() > startIndex)
			semanticStack.pop();
	}
	
	private void prepareStacks() {
		syntaxStack.clear();
		semanticStack.clear();
		operatorTokenStack.clear();
		functionTokenStack.clear();
		argumentStartIndexStack.clear();
	}
}
