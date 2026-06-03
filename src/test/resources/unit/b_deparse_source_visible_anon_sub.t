#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 8;
use B::Deparse;

my $deparse = B::Deparse->new;
my $one = $deparse->coderef2text(sub { 1 });
my $two = $deparse->coderef2text(sub { 2 });

like($one, qr/use warnings;\n    use strict;\n    1;/, 'source-visible anon sub includes active pragmas and body');
like($two, qr/use warnings;\n    use strict;\n    2;/, 'second source-visible anon sub keeps its own body');
isnt($one, $two, 'different anon sub bodies deparse distinctly');

my $inline_source = qq{#line 0 "-e"\nuse strict;\nuse warnings;\n#line 1 "-e"\nmy \$c = sub { 3 };\n};
my $inline = B::Deparse::_extract_source_visible_block($inline_source, 1, 3);
like($inline, qr/use warnings;\n    use strict;\n    3;/, 'source-visible anon sub honours inline #line directives');

my $leading_inline_source = qq{#line 0 "-e"\nuse strict;\nuse warnings;\n#line 1 "-e"\n\nEND { }\nmy \$c = sub { 4 };\n};
my $leading_inline = B::Deparse::_extract_source_visible_block($leading_inline_source, 3, 3);
like($leading_inline, qr/use warnings;\n    use strict;\n    4;/, 'source-visible anon sub honours leading inline lines');

my ($same_line_one, $same_line_two) = (sub { 5 }, sub { 6 });
my $same_one = $deparse->coderef2text($same_line_one);
my $same_two = $deparse->coderef2text($same_line_two);
like($same_one, qr/use warnings;\n    use strict;\n    5;/, 'source-visible anon sub finds first same-line body');
like($same_two, qr/use warnings;\n    use strict;\n    6;/, 'source-visible anon sub finds second same-line body');
isnt($same_one, $same_two, 'same-line anon sub bodies deparse distinctly');
