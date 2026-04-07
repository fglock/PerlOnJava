package ExtUtils::MM_PerlOnJava;

use strict;
use warnings;

# MM_PerlOnJava - ExtUtils::MakeMaker subclass for PerlOnJava
#
# This module handles the specifics of building/installing Perl modules
# on the PerlOnJava platform (Perl compiled to JVM bytecode).
#
# Key differences from MM_Unix:
# - No XS/C compilation (JVM can't load native libraries)
# - Simplified installation (direct copy to lib directory)
# - Tests run with jperl

use ExtUtils::MakeMaker::Config;
require ExtUtils::MM_Unix;
our @ISA = qw(ExtUtils::MM_Unix);

our $VERSION = '7.78';
$VERSION =~ tr/_//d;

# Override init_main to set PerlOnJava-specific defaults
sub init_main {
    my $self = shift;
    $self->SUPER::init_main(@_);
    
    # Don't try to write perllocal.pod or .packlist to jar:PERL5LIB
    # (which is not a real filesystem path)
    $self->{NO_PERLLOCAL} = 1;
    $self->{NO_PACKLIST} = 1;
    
    return;
}

# Installation base directory
sub _perlonjava_lib {
    return $ENV{PERLONJAVA_LIB} 
        || File::Spec->catdir($ENV{HOME} || '.', '.perlonjava', 'lib');
}

# Override: We don't support XS
sub xs_c {
    my $self = shift;
    return '';  # No XS compilation
}

sub xs_cpp {
    my $self = shift;
    return '';
}

sub xs_o {
    my $self = shift;
    return '';
}

# Override: Skip dynamic library creation
sub dynamic_lib {
    my $self = shift;
    return '';
}

sub dynamic_bs {
    my $self = shift;
    return '';
}

# Override: No static library either
sub static_lib {
    my $self = shift;
    return '';
}

# Override: Check for XS and warn
sub init_xs {
    my $self = shift;
    
    if ($self->{XS} && %{$self->{XS}}) {
        warn "\n";
        warn "=" x 60, "\n";
        warn "WARNING: This module contains XS code\n";
        warn "XS modules cannot be used directly with PerlOnJava.\n";
        warn "Consider:\n";
        warn "  1. Using a pure-Perl alternative\n";
        warn "  2. Porting the XS code to Java\n";
        warn "=" x 60, "\n\n";
    }
    
    return $self->SUPER::init_xs(@_);
}

# Override: Simplified test target
# Sets PERL5LIB to include blib/lib so test subprocesses can find the module
sub test {
    my($self, %attribs) = @_;
    
    my $tests = $attribs{TESTS} || '';
    if (!$tests && -d 't') {
        $tests = 't/*.t';
    }
    
    return '' unless $tests;
    
    # Set PERL5LIB to add blib/lib and blib/arch to @INC for test subprocesses
    # Test::Harness runs each test file as a subprocess, so we need PERL5LIB
    # Use "undef *Test::Harness::Switches" to disable the default -w switch,
    # matching standard ExtUtils::MakeMaker behavior (MM_Any::test_via_harness)
    return <<"MAKE_FRAG";
test :: pure_all
	PERL5LIB="\$(INST_LIB):\$(INST_ARCHLIB):\$\$PERL5LIB" \$(FULLPERL) "-MExtUtils::Command::MM" "-MTest::Harness" "-e" "undef *Test::Harness::Switches; test_harness(\$(TEST_VERBOSE), '\$(INST_LIB)', '\$(INST_ARCHLIB)')" $tests

test_dynamic :: pure_all
	PERL5LIB="\$(INST_LIB):\$(INST_ARCHLIB):\$\$PERL5LIB" \$(FULLPERL) "-MExtUtils::Command::MM" "-MTest::Harness" "-e" "undef *Test::Harness::Switches; test_harness(\$(TEST_VERBOSE), '\$(INST_LIB)', '\$(INST_ARCHLIB)')" $tests

test_static ::
	\@echo "No static tests for PerlOnJava"
MAKE_FRAG
}

1;

__END__

=head1 NAME

ExtUtils::MM_PerlOnJava - MakeMaker methods for PerlOnJava

=head1 SYNOPSIS

  # In ExtUtils/MM.pm, PerlOnJava is detected and this module is used

=head1 DESCRIPTION

This module provides ExtUtils::MakeMaker overrides specific to the
PerlOnJava platform. PerlOnJava compiles Perl to JVM bytecode, so:

=over 4

=item * XS/C code cannot be compiled (no native libraries on JVM)

=item * Installation is simplified (pure Perl only)

=item * Tests run under jperl

=back

=head1 SEE ALSO

L<ExtUtils::MakeMaker>, L<ExtUtils::MM_Unix>

=cut
