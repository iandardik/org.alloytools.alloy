package org.alloytools.alloy.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.alloytools.alloy.dto.SolutionDTO;
import org.alloytools.alloy.infrastructure.api.AlloyMain;
import org.alloytools.util.table.Table;

import aQute.lib.env.Env;
import aQute.lib.getopt.Arguments;
import aQute.lib.getopt.Description;
import aQute.lib.getopt.Options;
import aQute.lib.io.IO;
import aQute.lib.json.JSONCodec;
import aQute.libg.glob.Glob;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.A4SolutionWriter;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

import edu.mit.csail.sdg.alloy4.SafeList;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.translator.A4Tuple;
import edu.mit.csail.sdg.translator.A4TupleSet;
import edu.mit.csail.sdg.alloy4whole.SimpleGUI;
import java.util.*;

/**
 * 
 * @author aqute
 *
 */
@AlloyMain
public class CLI extends Env {
    private static void myAssert(boolean cond, String msg) {
        if (!cond) {
            throw new RuntimeException(msg);
        }
    }

    private static class Instance implements Comparable<Instance> {
        public A4Solution sol;
        public int score;
        public Instance(A4Solution s, int c) {
            this.sol = s;
            this.score = c;
        }
        @Override
        public int compareTo(Instance other) {
            return this.score - other.score;
        }
    }

	final A4Options options = new A4Options();

	public enum OutputType {
		none,plain, json, xml;
	}

	InputStream stdin = new FilterInputStream(System.in) {
		@Override
		public void close() throws IOException {
		}
	};
	PrintStream stdout = new PrintStream(new FilterOutputStream(System.out)) {
		public void close() {
			System.out.flush();
		};
	};

	/**
	 * Show the list of solvers
	 */

	/**
	 * Run all 'run' & asserts
	 */

	@Arguments(arg = "path")
	@Description("Execute an Alloy program")
	interface ExecOptions extends Options {
		@Description("The command to run. If no command is specified, the default command will run. The command may specify wildcards to run multiple commands. If the command is an integer, it will run the command with that index.")
		String command();

		@Description("Specify the output type: plain, json, xml, or none")
		OutputType type(OutputType deflt);

		@Description("Specify where the output should go. Default is the console")
		String output();

		@Description("If set, the solution will include only those models in which no arithmetic overflows occur")
		boolean nooverflow();

		@Description("Number of allowed recursion unrolls. Default is 0")
		int unrolls(int n);

		@Description("Depth for skolem analysis, default is 0")
		int depth(int n);

		@Description("Symmetry breaking removes instances that are symmetric to other instances. The parameter indicates the amount of effort Alloy can spend. The default is 20.")
		int symmetry(int n);

		@Description("Be quiet with progress information")
		boolean quiet();

		@Description("After resolving each command, start an evaluator")
		boolean evaluator();
	}

	/**
	 * Execute a Alloy program
	 * 
	 * @param options
	 *            the options to use
	 */
	@Description("Execute an Alloy program")
	public void _exec(ExecOptions options) throws Exception {
		SimpleReporter rep = new SimpleReporter(this);
		A4Options opt = this.options.dup();
		opt.noOverflow = options.nooverflow();
		opt.unrolls = options.unrolls(opt.unrolls);
		opt.skolemDepth = options.depth(opt.skolemDepth);
		opt.symmetry = options.depth(opt.symmetry);

		String filename = options._arguments().remove(0);
		File file = IO.getFile(filename);
		if (!file.isFile()) {
			error("No such file %s", file);
			return;
		}
		if (!file.canRead()) {
			error("Cannot read file %s", file);
			return;
		}

		Map<String, String> cache = new HashMap<>();
		CompModule world = CompUtil.parseEverything_fromFile(rep, cache, filename);

		List<Command> commands = world.getAllCommands();

		String cmd = options.command();
		Predicate<Command> run;
		if (cmd == null) {
			run = c -> true;
		} else if (cmd.matches("[0-9]+")) {
			int index = Integer.parseInt(cmd);
			if (index >= commands.size()) {
				error("command index %s is more than available commands %s", index, commands);
			}
			run = c -> commands.indexOf(c) == index;
		} else {
			Glob g = new Glob(cmd);
			run = c -> g.matches(c.label);
		}

		OutputStream out = output(options.output());
		if (out == null) {
			return;
		}

        SafeList<Func> funcs = world.getAllFunc();
        Func orderByFunc = null;
        Func groupByFunc = null;
        for (Func f : funcs) {
            final String label = f.toString();
            if (label.contains("orderBy")) {
                orderByFunc = f;
            }
            else if (label.contains("groupBy")) {
                groupByFunc = f;
            }
        }
        myAssert(orderByFunc != null || groupByFunc != null, "orderBy or groupBy function is required in the spec");
        System.out.println("using for orderByFun: " + orderByFunc);

		Map<CommandInfo, A4Solution> answers = new TreeMap<>();
		for (Command c : commands) {
			if (!run.test(c)) {
				trace("ignore command %s", c);
				continue;
			}

			//if (!options.quiet())
				//stdout.println("solving command " + c);

			long start = System.nanoTime();
			A4Solution s = TranslateAlloyToKodkod.execute_command(rep, world.getAllReachableSigs(), c,
					opt);
			long finish = System.nanoTime();

			CommandInfo info = new CommandInfo();
			info.command = c;
			info.durationInMs = TimeUnit.NANOSECONDS.toMillis(finish - start);
			answers.put(info, s);

            ArrayList<A4Solution> solutions = new ArrayList<A4Solution>();
            A4Solution it = s;
            while (it.satisfiable()) {
                solutions.add(it);
                it = it.next();
            }

            final int numInstances = solutions.size();
            Instance[] instances = new Instance[numInstances];
            for (int i = 0; i < numInstances; ++i) {
                final A4Solution sol = solutions.get(i);
                if (orderByFunc == null) {
                    Instance inst = new Instance(sol, 0);
                    instances[i] = inst;
                }
                else {
                    Object obj = sol.eval(orderByFunc.getBody());
                    A4TupleSet tupSet = (A4TupleSet) obj;

                    myAssert(tupSet.size() == 1, "orderBy should return a tuple of size 1");

                    final A4Tuple tup = tupSet.iterator().next();
                    final String strScore = tup.atom(0);
                    final int orderScore = Integer.parseInt(strScore);

                    Instance inst = new Instance(sol, orderScore);
                    instances[i] = inst;
                }
            }

            if (orderByFunc != null) {
                Arrays.sort(instances);
            }

            ArrayList<A4Solution> sortedInstances = new ArrayList<A4Solution>();
            Set<Integer> groupsSeen = new HashSet<Integer>();
            for (int i = 0; i < numInstances; ++i) {
                final Instance inst = instances[i];
                if (groupByFunc == null) {
                    sortedInstances.add(inst.sol);
                }
                else {
                    final A4Solution sol = inst.sol;

                    Object obj = sol.eval(groupByFunc.getBody());
                    A4TupleSet tupSet = (A4TupleSet) obj;
                    myAssert(tupSet.size() == 1, "groupBy should return a tuple of size 1");
                    final A4Tuple tup = tupSet.iterator().next();
                    final String strScore = tup.atom(0);
                    final int groupScore = Integer.parseInt(strScore);

                    if (!groupsSeen.contains(groupScore)) {
                        sortedInstances.add(inst.sol);
                    }
                    groupsSeen.add(groupScore);
                }
            }

            SimpleGUI.main(sortedInstances);
		}

        /*
		if (!options.quiet() && answers.isEmpty()) {
			stdout.println("no commands found " + cmd);
		}

		generate(world, answers, options.type(OutputType.plain), out);

		if (options.evaluator() && !answers.isEmpty()) {
			evaluator(world, answers);
		}
        */
	}

	private void evaluator(CompModule world, Map<CommandInfo, A4Solution> answers) throws Exception {
		for (Entry<CommandInfo, A4Solution> s : answers.entrySet()) {
			A4Solution sol = s.getValue();
			if (sol.satisfiable()) {
				stdout.println("Evaluator for " + s.getKey().command);
				stdout.flush();
				Evaluator e = new Evaluator(world, sol, stdin, stdout);
				String lastCommand = e.loop();
				if ( lastCommand.equals("/exit"))
					break;
			}
		}
		stdout.println("bye");
	}

	@Arguments(arg = "path")
	@Description("List the commands in an alloy file")
	interface CommandsOptions extends Options {

	}

	/**
	 * Execute a Alloy program
	 * 
	 * @param options
	 *            the options to use
	 */
	@Description("Show all commands in an Alloy program")
	public void _commands(CommandsOptions options) throws Exception {
		SimpleReporter rep = new SimpleReporter(this);
		String filename = options._arguments().remove(0);
		Map<String, String> cache = new HashMap<>();
		CompModule world = CompUtil.parseEverything_fromFile(rep, cache, filename);
		for (Command c : world.getAllCommands()) {
			stdout.println(c);
		}
	}

	private OutputStream output(String output) throws IOException {

		if (output == null) {
			return stdout;
		}

		File file = IO.getFile(output);
		file.getParentFile().mkdirs();
		if (!file.getParentFile().isDirectory()) {
			error("Cannot create parent directory for %s", file);
			return null;
		}
		if (!file.canWrite()) {
			error("Cannot write %s", file);
			return null;
		}

		return new FileOutputStream(file);
	}

	private void generate(CompModule world, Map<CommandInfo, A4Solution> s, OutputType type, OutputStream out)
			throws Exception {
		try {
			switch (type) {
			default:
			case none:
				return;
			case plain:
				Table overall = new Table(s.size() * 2, 1, 0);
				int n = 0;
				for (Map.Entry<CommandInfo, A4Solution> e : s.entrySet()) {
					Table info = new Table(2, 2, 0);
					info.set(0, 0, "Command");
					info.set(0, 1, e.getKey().command);
					info.set(1, 0, "Duration in ms");
					info.set(1, 1, e.getKey().durationInMs);
					overall.set(n, 0, info);
					overall.set(n + 1, 0, e.getValue().toTable());
					n += 2;
				}
				IO.store(overall, out);
				break;

			case json:
				List<SolutionDTO> trace = new ArrayList<>();

				for (Map.Entry<CommandInfo, A4Solution> e : s.entrySet()) {
					A4Solution a4s = e.getValue();
					a4s.setModule(world);
					trace.add(a4s.toDTO());
				}
				JSONCodec codec = new JSONCodec();
				codec.enc().writeDefaults().indent("  ").to(out).put(trace);
				break;

			case xml:
				try (PrintWriter pw = new PrintWriter(out)) {
					if (s.size() > 1) {
						pw.println("<top>");
					}
					for (Map.Entry<CommandInfo, A4Solution> e : s.entrySet()) {
						A4SolutionWriter.writeInstance(null, e.getValue(), pw, Collections.emptyList(),
								Collections.emptyMap());
					}
					if (s.size() > 1) {
						pw.println("</top>");
					}
				}
				break;

			}
		} finally {
			IO.close(out);
		}
	}

	@Override
	public String toString() {
		return "CLI";
	}
}
