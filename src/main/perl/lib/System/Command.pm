package System::Command;
$System::Command::VERSION = '1.122';
use warnings;
use strict;
use 5.006;

use Carp;
use Cwd qw( cwd );
use IO::Handle;
use Symbol ();
use Scalar::Util qw( blessed reftype );
use List::Util qw( reduce );
use System::Command::Reaper;

use Config;
use Fcntl qw( F_GETFD F_SETFD FD_CLOEXEC );

# MSWin32 support
use constant MSWin32 => $^O eq 'MSWin32';
require IPC::Run if MSWin32;

our $QUIET = 0;

# trace setup at startup
my $_trace_opts = sub {
    my ( $trace, $file, $th ) = split /=/, shift, 2;
    open $th, '>>', $file or carp "Can't open $file: $!" if $file;
    $th ||= *STDERR;
    return ( $trace, $th );
};
my @trace;
@trace = $_trace_opts->( $ENV{SYSTEM_COMMAND_TRACE} )
    if $ENV{SYSTEM_COMMAND_TRACE};

sub import {
    my ( $class, @args ) = @_;
    my %arg = ( quiet => sub { $QUIET = 1 } );
    for my $arg (@args) {
        $arg =~ s/^-//;    # allow dashed options
        croak "Unknown option '$arg' in 'use System::Command'"
            if !exists $arg{$arg};
        $arg{$arg}->();
    }
}

# a few simple accessors
{
    no strict 'refs';
    for my $attr (qw( pid stdin stdout stderr options )) {
        *$attr = sub { return $_[0]{$attr} };
    }
    for my $attr (qw( exit signal core )) {
        no strict 'refs';
        *$attr = sub { $_[0]->is_terminated(); return $_[0]{$attr} };
    }
    for my $attr (qw( cmdline )) {
        *$attr = sub { return @{ $_[0]{$attr} } };
    }
}

# REALLY PRIVATE FUNCTIONS
# PerlOnJava detection: PerlOnJava has no fork() — use IPC::Open3 instead.
# See dev/modules/git_modules_support.md in the PerlOnJava repository.
use constant PERLONJAVA => defined $Config{perlonjava};
require IPC::Open3 if PERLONJAVA;

# a sub-process spawning function
my $_spawn = sub {
    my ($o, @cmd) = @_;
    my $pid;

    # setup filehandles
    my $in  = Symbol::gensym;
    my $out = Symbol::gensym;
    my $err = Symbol::gensym;

    # no buffering on pipes used for writing
    select( ( select($in), $| = 1 )[0] );

    # start the command
    if (PERLONJAVA) {

        # PerlOnJava has no fork(). Delegate to IPC::Open3, which is
        # implemented on top of java.lang.ProcessBuilder. cwd and env
        # are already handled by the caller (System::Command::new)
        # via chdir + `local %ENV`.
        $pid = eval { IPC::Open3::open3($in, $out, $err, @cmd) };
        if (my $err_msg = $@) {
            # Translate IPC::Open3's "open3: exec of X failed: Y" into
            # System::Command's own fork-path format
            # "Can't exec( @cmd ): Y", so error-handling tests (e.g.
            # t/11-spawn-fail.t) see the expected string in $@.
            if ($err_msg =~ /^open3: exec of \S+ failed: (.*)$/m) {
                croak "Can't exec( @cmd ): $1";
            }
            die $err_msg;
        }
    }
    elsif (MSWin32) {
        $pid = IPC::Run::start(
            [@cmd],
            '<pipe'  => $in,
            '>pipe'  => $out,
            '2>pipe' => $err,
        );
    }
    else {

        # the code below takes inspiration from IPC::Open3 and Sys::Cmd

        # create handles for the child process (using CAPITALS)
        my $IN  = Symbol::gensym;
        my $OUT = Symbol::gensym;
        my $ERR = Symbol::gensym;

        # no buffering on pipes used for writing
        select( ( select($OUT), $| = 1 )[0] );
        select( ( select($ERR), $| = 1 )[0] );

        # connect parent and child with pipes
        pipe $IN,  $in  or croak "input pipe(): $!";
        pipe $out, $OUT or croak "output pipe(): $!";
        pipe $err, $ERR or croak "errput pipe(): $!";

        # an extra pipe to communicate exec() failure
        pipe my ( $stat_r, $stat_w );

        # create the child process
        $pid = fork;
        croak "Can't fork: $!" if !defined $pid;

        if ($pid) {

            # parent won't use those handles
            close $stat_w;
            close $IN;
            close $OUT;
            close $ERR;

            # failed to fork+exec?
            my $mesg = do { local $/; <$stat_r> };
            die $mesg if $mesg;
        }
        else {    # kid

            # use $stat_r to communicate errors back to the parent
            eval {

                # child won't use those handles
                close $stat_r;
                close $in;
                close $out;
                close $err;

                # setup process group if possible
                setpgrp 0, 0 if $o->{setpgrp} && $Config{d_setpgrp};

                # close $stat_w on exec
                my $flags = fcntl( $stat_w, F_GETFD, 0 )
                    or croak "fcntl GETFD failed: $!";
                fcntl( $stat_w, F_SETFD, $flags | FD_CLOEXEC )
                    or croak "fcntl SETFD failed: $!";

                # associate STDIN, STDOUT and STDERR to the pipes
                my ( $fd_IN, $fd_OUT, $fd_ERR )
                    = ( fileno $IN, fileno $OUT, fileno $ERR );
                open \*STDIN, "<&=$fd_IN"
                    or croak "Can't open( \\*STDIN, '<&=$fd_IN' ): $!";
                open \*STDOUT, ">&=$fd_OUT"
                    or croak "Can't open( \\*STDOUT, '<&=$fd_OUT' ): $!";
                open \*STDERR, ">&=$fd_ERR"
                    or croak "Can't open( \\*STDERR, '<&=$fd_ERR' ): $!";

                # and finally, exec into @cmd
                exec( { $cmd[0] } @cmd )
                    or do { croak "Can't exec( @cmd ): $!"; }
            };

            # something went wrong
            print $stat_w $@;
            close $stat_w;

            # DIE DIE DIE
            eval { require POSIX; POSIX::_exit(255); };
            exit 255;
        }
    }

    return ( $pid, $in, $out, $err );
};

my $_dump_ref = sub {
    require Data::Dumper;    # only load if needed
    local $Data::Dumper::Indent    = 0;
    local $Data::Dumper::Purity    = 0;
    local $Data::Dumper::Maxdepth  = 0;
    local $Data::Dumper::Quotekeys = 0;
    local $Data::Dumper::Sortkeys  = 1;
    local $Data::Dumper::Useqq     = 1;
    local $Data::Dumper::Terse     = 1;
    return Data::Dumper->Dump( [shift] );
};

my $_do_trace = sub {
    my ( $trace, $th, $pid, $cmd, $o ) = @_;
    print $th "System::Command cmd[$pid]: ",
        join( ' ', map /\s/ ? $_dump_ref->($_) : $_, @$cmd ), "\n";
    print $th map "System::Command opt[$pid]: $_->[0] => $_->[1]\n",
        map [ $_ => $_dump_ref->( $o->{$_} ) ],
        grep { $_ ne 'env' } sort keys %$o
        if $trace > 1;
    print $th map "System::Command env[$pid]: $_->[0] => $_->[1]\n",
        map [ $_ => $_dump_ref->( $o->{env}{$_} ) ],
        keys %{ $o->{env} || {} }
        if $trace > 2;
};

# module methods
sub new {
    my ( $class, @cmd ) = @_;

    # split the args
    my @o = { setpgrp => 1 };
    @cmd = grep { !( ref eq 'HASH' ? push @o, $_ : 0 ) } @cmd;

    # merge the option hashes
    my $o = reduce {
        +{  %$a, %$b,
            exists $a->{env} && exists $b->{env}
            ? ( env => { %{ $a->{env} }, %{ $b->{env} } } )
            : ()
        };
    }
    @o;

    # open the trace file before changing directory
    my ( $trace, $th );
    ( $trace, $th ) = $_trace_opts->( $o->{trace} ) if $o->{trace};
    ( $trace, $th ) = @trace if @trace;    # environment override

    # chdir to the expected directory
    my $orig = cwd;
    my $dest = defined $o->{cwd} ? $o->{cwd} : undef;
    if ( defined $dest ) {
        chdir $dest or croak "Can't chdir to $dest: $!";
    }

    # keep changes to the environment local
    local %ENV = %ENV;

    # update the environment
    if ( exists $o->{env} ) {
        croak "ENV variables cannot be empty strings on Win32"
            if MSWin32 and grep { defined and !length } values %{ $o->{env} };
        @ENV{ keys %{ $o->{env} } } = values %{ $o->{env} };
        delete $ENV{$_}
            for grep { !defined $o->{env}{$_} } keys %{ $o->{env} };
    }

    # interactive mode requested
    if ( $o->{interactive} ) {
        croak "Can't run command in interactive mode: not a terminal"
          unless -t STDIN;

        system { $cmd[0] } @cmd;

        my $self = bless {
            cmdline => [@cmd],
            options => $o,
            stdin   => IO::Handle->new,
            stdout  => IO::Handle->new,
            stderr  => IO::Handle->new,
            exit    => $? >> 8,
            signal  => $? & 127,
            core    => $? & 128,
        }, $class;

        defined reftype( $o->{$_} )
          and reftype( $o->{$_} ) eq 'SCALAR'
          and ${ $o->{$_} } = $self->{$_}
          for qw( exit signal core );

        return $self;
    }

    # start the command
    my ( $pid, $in, $out, $err ) = eval { $_spawn->( $o, @cmd ); };

    # FIXME - better check error conditions
    if ( !defined $pid ) {
        $_do_trace->( $trace, $th, '!', \@cmd, $o ) if $trace;
        croak $@;
    }

    # trace is mostly a debugging tool
    $_do_trace->( $trace, $th, $pid, \@cmd, $o ) if $trace;

    # some input was provided
    if ( defined $o->{input} ) {
        local $SIG{PIPE}
            = sub { croak "Broken pipe when writing to: @cmd" }
            if $Config{sig_name} =~ /\bPIPE\b/;
        print {$in} $o->{input} if length $o->{input};
        $in->close;
    }

    # chdir back to origin
    if ( defined $dest ) {
        chdir $orig or croak "Can't chdir back to $orig: $!";
    }

    # create the object
    my $self = bless {
        cmdline  => [@cmd],
        options  => $o,
        pid      => MSWin32 ? $pid->{KIDS}[0]{PID} : $pid,
        stdin    => $in,
        stdout   => $out,
        stderr   => $err,
      ( _ipc_run => $pid )x!! MSWin32,
    }, $class;

    # create the subprocess reaper and link the handles and command to it
    ${*$in} = ${*$out} = ${*$err} = $self->{reaper}    # typeglobs FTW
      = System::Command::Reaper->new( $self, { trace => $trace, th => $th } );

    return $self;
}

sub spawn {
    my ( $class, @cmd ) = @_;
    return @{ $class->new(@cmd) }{qw( pid stdin stdout stderr )};
}

sub loop_on {
    my $self = shift;

    # handle options and defaults
    my %args = (
        stderr => sub { print STDERR shift },
        @_
    );
    for my $which ( grep exists $args{$_}, qw( stdout stderr ) ) {
        if ( $args{$which} ) {
            croak "'$which' option must be a CODE reference"
              if reftype $args{$which} ne 'CODE';
        }
        else {
            delete $args{$which};
        }
    }

    # create an object for the class method
    if ( !ref $self ) {
        die "'command' attribute required by loop_on when used as a class method"
          if !exists $args{command};
        $self = $self->new( @{ $args{command} } );
    }

    require IO::Select;
    my $select = IO::Select->new( $self->stdout, $self->stderr );

    local $/ = $args{input_record_separator}
      if exists $args{input_record_separator};

    # loop until end of streams
    while ( my @ready = $select->can_read ) {
        for my $fh (@ready) {
            my $which = $fh == $self->stdout ? 'stdout' : 'stderr';
            if ( defined( my $line = <$fh> ) ) {
                my $ret = 1;
                $ret = $args{$which}->($line)
                  if exists $args{$which};
                return if !$ret;
            }
            else {
                $select->remove($fh);
                $fh->close;
            }
        }
    }

    # close all pipes and wait for the child to terminate
    $self->close;

    # success in the Unix sense
    return defined $self->exit && $self->exit == 0;
}

# delegate those to the reaper (when there's one)
sub is_terminated {
    return $_[0]{options}{interactive}
      ? 1
      : $_[0]{reaper}->is_terminated();
}

sub close {
    $_[0]{reaper}->close() unless $_[0]{options}{interactive};
    return $_[0];
}

1;

__END__

=pod

=head1 NAME

System::Command - Object for running system commands

=head1 VERSION

version 1.122

=head1 SYNOPSIS

    use System::Command;

    # invoke an external command, and return an object
    $cmd = System::Command->new( @cmd );

    # options can be passed as a hashref
    $cmd = System::Command->new( @cmd, \%option );

    # $cmd is basically a hash, with keys / accessors
    $cmd->stdin();     # filehandle to the process stdin (write)
    $cmd->stdout();    # filehandle to the process stdout (read)
    $cmd->stderr();    # filehandle to the process stdout (read)
    $cmd->pid();       # pid of the child process

    # find out if the child process died
    if ( $cmd->is_terminated() ) {
        # the handles are not closed yet
        # but $cmd->exit() et al. are available if it's dead
    }

    # done!
    $cmd->close();

    # exit information
    $cmd->exit();      # exit status
    $cmd->signal();    # signal
    $cmd->core();      # core dumped? (boolean)

    # cut to the chase
    my ( $pid, $in, $out, $err ) = System::Command->spawn(@cmd);

=head1 DESCRIPTION

System::Command is a class that launches external system commands
and return an object representing them, allowing to interact with them
through their C<STDIN>, C<STDOUT> and C<STDERR> handles.

=head1 METHODS

System::Command supports the following methods:

=head2 new

    my $cmd = System::Command->new( @cmd )

Runs an external command using the list in C<@cmd>.

If C<@cmd> contains a hash reference, it is taken as an I<option> hash.

If several option hashes are passed to C<new()>, they will be merged
together with individual values being overridden by those (with the same
key) from hashes that appear later in the list.

To allow subclasses to support their own set of options, unrecognized
options are silently ignored.

The recognized keys are:

=over 4

=item C<cwd>

The I<current working directory> in which the command will be run.

=item C<env>

A hashref containing key / values to add to the command environment.

If several option hashes define the C<env> key, the hashes they point
to will be merged into one (instead of the last one taking precedence).

If a value is C<undef>, the variable corresponding to the key will
be I<removed> from the environment.

=item C<input>

A string that is send to the command's standard input, which is then closed.

Using the empty string as C<input> will close the command's standard input
without writing to it.

Using C<undef> as C<input> will not do anything. This behaviour provides
a way to modify previous options populated by some other part of the program.

On some systems, some commands may close standard input on startup,
which will cause a SIGPIPE when trying to write to it. This will raise
an exception.

=item C<interactive>

If true, the command will actually be run using the L<perlfunc/system>
builtin. If C<STDIN> is not a terminal, the constructor will die.

Not reaper object will be created, and the C<stdin>, C<stdout> and
C<stderr> filehandles will point to dummy closed handles. The C<exit>,
C<signal> and C<core> attributes will be correctly set.

(Added in version 1.114.)

=item C<setpgrp>

By default, the spawned process is made the leader of its own process
group using C<setpgrp( 0, 0 )> (if possible). This enables sending a
signal to the command and all its child processes at once:

    # negative signal is sent to the process group
    kill -SIGKILL, $cmd->pid;

Setting the C<setpgrp> option to a false value disables this behaviour.

(Added in version 1.110.)

=item C<trace>

The C<trace> option defines the trace settings for System::Command.
The C<SYSTEM_COMMAND_TRACE> environment variable can be used to specify
a global trace setting at startup. The environment variable overrides
individual C<trace> options.

If C<trace> or C<SYSTEM_COMMAND_TRACE> contains an C<=> character then
what follows it is used as the name of the file to append the trace to.
When using the C<trace> option, it is recommended to use an absolute
path for the trace file, in case the main program C<chdir()> before
calling System::Command.

At trace level 1, only the command line is shown:

    System::Command cmd[12834]: /usr/bin/git commit -m "Test option hash in new()"

Note: Command-line parameters containing whitespace will be properly quoted.

At trace level 2, the options values are shown:

    System::Command opt[12834]: cwd => "/tmp/kHkPUBIVWd"
    System::Command opt[12834]: fatal => {128 => 1,129 => 1}
    System::Command opt[12834]: git => "/usr/bin/git"

Note: The C<fatal> and C<git> options in the example above are actually
used by L<Git::Repository> to determine the command to be run, and
ignored by System::Command. References are dumped using L<Data::Dumper>.

At trace level 3, the content of the C<env> option is also listed:

    System::Command env[12834]: GIT_AUTHOR_EMAIL => "author\@example.com"
    System::Command env[12834]: GIT_AUTHOR_NAME => "Example author"

If the command cannot be spawned, the trace will show C<!> instead of
the pid:

    System::Command cmd[!]: does-not-exist

(Added in version 1.108.)

=item exit

=item core

=item signal

The above three options can be set to point to a reference to a scalar,
which will be automatically updated when the command is terminated. See
the L</Accessors> section for details about what the attributes of the
same name mean.

(Added in version 1.114.)

=back

The System::Command object returned by C<new()> has a number of
attributes defined (see below).


=head2 close

    $cmd->close;

Close all pipes to the child process, collects exit status, etc.
and defines a number of attributes (see below).

Returns the invocant, so one can do things like:

    my $exit = $cmd->close->exit;

=head2 is_terminated

    if ( $cmd->is_terminated ) {...}

Returns a true value if the underlying process was terminated.

If the process was indeed terminated, collects exit status, etc.
and defines the same attributes as C<close()>, but does B<not> close
all pipes to the child process.


=head2 spawn

    my ( $pid, $in, $out, $err ) = System::Command->spawn(@cmd);

This shortcut method calls C<new()> (and so accepts options in the same
manner) and directly returns the C<pid>, C<stdin>, C<stdout> and C<stderr>
attributes, in that order.

(Added in version 1.01.)

=head2 loop_on

    $cmd->loop_on(
        stdout => sub { ... },
        stderr => sub { ... },
    );

This method calls the corresponding code references with each line
produced on the standard output and errput of the command.

If the C<stdout> or C<stderr> argument is not given, the default is to
silently drop the data for C<stdout>, and to pass through (to STDERR)
the data for C<stderr>. To prevent any processing, pass a false value
to the parameter.

For example, the following line will silently run the command to
completion:

    $cmd->loop_on( stderr => '' );

The method blocks until the command is completed (or rather, until
its output and errput handles have been closed), or until one of the
callbacks returns a false value.

Data is read using L<readline|perlfunc/readline>, which depends on C<$/>
for its definition of a "line". To that effect, the method takes a third
optional argument, C<input_record_separator>, which sets the value for
C<$/> for the duration of the call.

I<Caveat Emptor>: since C<loop_on> is line-based, it may B<block> if
either output or errput sends incomplete lines (e.g. if the command is
some sort of interactive shell with a prompt).

The return value is true if the command exited with status 0, and false
otherwise (i.e. the Unix traditional definition of success).

(Added in version 1.117.)

=head2 Accessors

The attributes of a System::Command object are also accessible
through a number of accessors.

The object returned by C<new()> will have the following attributes defined:

=over 4

=item cmdline

Return the command-line actually executed, as a list of strings.

=item options

The merged list of options used to run the command.

=item pid

The PID of the underlying command.

=item stdin

A filehandle opened in write mode to the child process' standard input.

=item stdout

A filehandle opened in read mode to the child process' standard output.

=item stderr

A filehandle opened in read mode to the child process' standard error output.

=back

Regarding the handles to the child process, note that in the following code:

    my $fh = System::Command->new( @cmd )->stdout;

C<$fh> is opened and points to the output handle of the child process,
while the anonymous System::Command object has been destroyed. Once
C<$fh> is destroyed, the subprocess will be reaped, thus avoiding zombies.
(L<System::Command::Reaper> undertakes this process.)

After the call to C<close()> or after C<is_terminated()> returns true,
the following attributes will be defined (note that the accessors
always run C<is_terminated()>, to improve their chance of getting
a value if the process just finished):

=over 4

=item exit

The exit status of the underlying command.

=item signal

The signal, if any, that killed the command.

=item core

A boolean value indicating if the command dumped core.

=back

Even when not having a reference to the System::Command object any more,
it's still possible to get the C<exit>, C<core> or C<signal> values,
using the options of the same name:

    my $fh = System::Command->new( @cmd, { exit => \my $exit } )->stdout;

Once the command is terminated, the C<$exit> variable will contain the
value that would have been returned by the C<exit()> method.

=head1 CAVEAT EMPTOR

Note that System::Command uses C<waitpid()> to catch the status
information of the child processes it starts. This means that if your
code (or any module you C<use>) does something like the following:

    local $SIG{CHLD} = 'IGNORE';    # reap child processes

System::Command will not be able to capture the C<exit>, C<signal>
and C<core> attributes. It will instead set all of them to the
impossible value C<-1>, and display the warning
C<Child process already reaped, check for a SIGCHLD handler>.

To silence this warning (and accept the impossible status information),
load System::Command with:

    use System::Command -quiet;

It is also possible to more finely control the warning by setting
the C<$System::Command::QUIET> variable (the warning is not emitted
if the variable is set to a true value).

If the subprocess started by System::Command has a short life
expectancy, and no other child process is expected to die during that
time, you could even disable the handler locally (use at your own risks):

    {
        local $SIG{CHLD};
        my $cmd = System::Command->new(@cmd);
        ...
    }

=head1 AUTHOR

Philippe Bruhat (BooK), C<< <book at cpan.org> >>

=head1 ACKNOWLEDGEMENTS

Thanks to Alexis Sukrieh (SUKRIA) who, when he saw the description of
L<Git::Repository::Command> during my talk at OSDC.fr 2010, asked
why it was not an independent module. This module was started by
taking out of L<Git::Repository::Command> 1.08 the parts that
weren't related to Git.

Thanks to Christian Walde (MITHALDU) for his help in making this
module work better under Win32.

The L<System::Command::Reaper> class was added after the addition
of Git::Repository::Command::Reaper in L<Git::Repository::Command> 1.11.
It was later removed from L<System::Command> version 1.03, and brought
back from the dead to deal with the zombie apocalypse in version 1.106.
The idea of a reaper class comes from Vincent Pit.

Thanks to Tim Bunce for using L<Git::Repository> and making many
suggestions based on his use and needs. Most of them turned into
improvement for System::Command instead, once we figured out that the
more general feature idea really belonged there.

=head1 BUGS

Please report any bugs or feature requests to C<bug-system-command at rt.cpan.org>, or through
the web interface at L<http://rt.cpan.org/NoAuth/ReportBug.html?Queue=System-Command>.  I will be notified, and then you'll
automatically be notified of progress on your bug as I make changes.

=head1 SUPPORT

You can find documentation for this module with the perldoc command.

    perldoc System::Command


You can also look for information at:

=over 4

=item * RT: CPAN's request tracker

L<http://rt.cpan.org/NoAuth/Bugs.html?Dist=System-Command>

=item * AnnoCPAN: Annotated CPAN documentation

L<http://annocpan.org/dist/System-Command>

=item * CPAN Ratings

L<http://cpanratings.perl.org/d/System-Command>

=item * Search CPAN

L<http://search.cpan.org/dist/System-Command/>

=back


=head1 COPYRIGHT

Copyright 2010-2016 Philippe Bruhat (BooK).

=head1 LICENSE

This program is free software; you can redistribute it and/or modify it
under the terms of either: the GNU General Public License as published
by the Free Software Foundation; or the Artistic License.

See L<http://dev.perl.org/licenses/> for more information.

=cut
