package PadWalker;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '2.5';
our @EXPORT_OK = qw(peek_my peek_our closed_over peek_sub var_name set_closed_over);
our %EXPORT_TAGS = (all => \@EXPORT_OK);

# PadWalker is implemented in XS on CPAN. PerlOnJava cannot inspect JVM
# closure frames through that API, but callers such as JSON::Eval only need to
# distinguish serializable, self-contained coderefs. JVM subroutines expose no
# Perl pad to walk, so an empty result is the correct answer for them.
sub closed_over { return {}; }

sub _unsupported {
    die "PadWalker::$_[0] is not implemented on PerlOnJava\n";
}

sub peek_my         { _unsupported('peek_my') }
sub peek_our        { _unsupported('peek_our') }
sub peek_sub        { _unsupported('peek_sub') }
sub var_name        { _unsupported('var_name') }
sub set_closed_over { Internals::jperl_set_closed_over(@_) }

1;
