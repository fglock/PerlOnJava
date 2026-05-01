package Module::Build::Base;

# PerlOnJava workaround: Override have_forkpipe to disable fork pipes
# JVM doesn't support true fork(), so we make Module::Build use backticks instead

use strict;
use warnings;
use Cwd qw(abs_path);

# Remove this file from %INC so the real Module::Build::Base can be loaded
delete $INC{'Module/Build/Base.pm'};

# Find and load the real Module::Build::Base
# We need to skip files that match our stub (in jar or this file)
my $loaded = 0;
for my $inc_path (@INC) {
    next if $inc_path =~ /^jar:/;  # Skip jar entries (that's where our stub is)
    next unless -d $inc_path;
    my $file = "$inc_path/Module/Build/Base.pm";
    if (-f $file) {
        # Ensure the inc_path is in @INC so dependencies can be found
        # (do() doesn't automatically add the directory to @INC like require does)
        unshift @INC, $inc_path unless grep { $_ eq $inc_path } @INC;
        
        # Get absolute path for do() since '.' is not in @INC
        my $abs_file = abs_path($file);
        
        # Load the real module using do() with absolute path
        my $result = do $abs_file;
        if ($@) {
            die "Error loading Module::Build::Base from $file: $@";
        }
        unless (defined $result) {
            die "Failed to load Module::Build::Base from $file: $!";
        }
        $INC{'Module/Build/Base.pm'} = $file;
        $loaded = 1;
        last;
    }
}

if (!$loaded) {
    # If we can't find a real Module::Build::Base, that's fine - just provide the stub
    # Define have_forkpipe to return 0
    *have_forkpipe = sub { 0 };
} else {
    # Now override have_forkpipe to return false
    # This makes _backticks() use backticks instead of fork+pipe
    no warnings 'redefine';
    *have_forkpipe = sub { 0 };

    # Auto-enable pureperl_only for modules that support it.
    # PerlOnJava runs on JVM and cannot compile XS/C code.
    # Module::Build's process_xs_files() only skips XS when BOTH
    # pureperl_only AND allow_pureperl are true.  Since pureperl_only
    # defaults to 0, modules like Params::Validate (allow_pureperl=1)
    # still attempt XS compilation and die.  Fix: auto-set pureperl_only
    # when the module declares allow_pureperl and no C compiler exists.
    my $orig_process_xs_files = \&Module::Build::Base::process_xs_files;
    *Module::Build::Base::process_xs_files = sub {
        my $self = shift;
        if ($self->allow_pureperl && !$self->have_c_compiler) {
            $self->pureperl_only(1);
            return;
        }
        return $self->$orig_process_xs_files(@_);
    };

    my $orig_process_support_files = \&Module::Build::Base::process_support_files;
    *Module::Build::Base::process_support_files = sub {
        my $self = shift;
        if ($self->allow_pureperl && !$self->have_c_compiler) {
            return;
        }
        return $self->$orig_process_support_files(@_);
    };

    # Preserve PERL5LIB that CPAN's set_perl5lib injects for tested-but-not-yet-installed
    # dependencies.  The original run_perl_command sets PERL5LIB to only _added_to_INC,
    # which clobbers any blib paths for peer modules (e.g. Pod::Wrap when testing Pod::Tidy).
    no warnings 'redefine';
    *Module::Build::Base::run_perl_command = sub {
        my ($self, $args) = @_;
        $args = [ $self->split_like_shell($args) ] unless ref($args);
        my $perl = ref($self) ? $self->perl : $self->find_perl_interpreter;

        # Merge our local blib additions with whatever PERL5LIB CPAN already set
        my @added = $self->_added_to_INC;
        my $sep   = $self->config('path_sep');
        my @parts = @added;
        push @parts, $ENV{PERL5LIB}
            if defined $ENV{PERL5LIB} && length $ENV{PERL5LIB};
        local $ENV{PERL5LIB} = join $sep, @parts;

        return $self->do_system($perl, @$args);
    };
}

1;
