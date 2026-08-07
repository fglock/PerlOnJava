package PadWalker;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '2.5';
our @EXPORT_OK = qw(peek_my peek_our closed_over peek_sub var_name set_closed_over);
our %EXPORT_TAGS = (all => \@EXPORT_OK);

# PerlOnJava records captured lexical containers on both compiled and
# interpreted closures, so the runtime can expose the live references that
# PadWalker callers expect.
sub closed_over { Internals::jperl_closed_over(@_) }
sub peek_sub    { Internals::jperl_peek_sub(@_) }

sub _unsupported {
    die "PadWalker::$_[0] is not implemented on PerlOnJava\n";
}

sub peek_my         { _unsupported('peek_my') }
sub peek_our        { _unsupported('peek_our') }
sub var_name        { _unsupported('var_name') }
sub set_closed_over { Internals::jperl_set_closed_over(@_) }

1;
