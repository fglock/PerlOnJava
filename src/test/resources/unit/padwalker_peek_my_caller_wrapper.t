use strict;
use warnings;
use Test::More tests => 2;
use PadWalker qw(peek_my);

my $dumper_names_lexical = 'caller lexical';

sub dumper_names_wrapper {
    my $pad = peek_my(1);
    return $pad->{'$dumper_names_lexical'};
}

sub dumper_names_caller_scope {
    my $capture_pad_lexical = $dumper_names_lexical;
    return dumper_names_wrapper();
}

my $reference = dumper_names_caller_scope();
ok(defined $reference, 'peek_my finds a lexical through a Perl wrapper');
is($$reference, 'caller lexical', 'peek_my returns the top-level caller lexical');
