use strict;
use warnings;
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More tests => 1;

use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::UnicodeGenerator qw(select_perl_root);

my $root = tempdir(CLEANUP => 1);
my $explicit = File::Spec->catdir($root, 'explicit-perl');
local $ENV{PERLONJAVA_PERL_ROOT} = $explicit;

eval {
    select_perl_root(
        repo_root => $root,
        required => ['uni_keywords.h'],
    );
};
like($@,
    qr/^No complete current Perl source tree: \Q$explicit\E missing uni_keywords\.h/,
    'missing explicit Perl roots use current-source terminology');
