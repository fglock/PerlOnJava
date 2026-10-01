use strict;
use warnings;
use Test::More;

eval q{ no strict; use 5.012; ${"foo"} = "bar"; };
is $@, '', 'explicit no strict survives a later version declaration';

eval q{ use strict; use 5.010; ${"foo"} = "bar"; };
like $@, qr/strict refs/, 'version declaration does not erase explicit use strict';

{
    package UseVersionSemantics::WithVersion;
    our $VERSION = 35.36;
}
my $version_error = eval {
    UseVersionSemantics::WithVersion->VERSION(v100.105);
    '';
} || $@;
like $version_error,
    qr/UseVersionSemantics::WithVersion version v100\.105\.0 required--this is only version v35\.360\.0/,
    'v-string version errors preserve Perl version formatting';

my $string_version_error = eval {
    UseVersionSemantics::WithVersion->VERSION('v100.105');
    '';
} || $@;
like $string_version_error,
    qr/UseVersionSemantics::WithVersion version v100\.105\.0 required--this is only version v35\.360\.0/,
    'stringified v-string requirements retain v-string formatting';

{
    local $UseVersionSemantics::WithVersion::VERSION = v35.36;
    my $numeric_version_error = eval {
        UseVersionSemantics::WithVersion->VERSION(100.105);
        '';
    } || $@;
    like $numeric_version_error,
        qr/UseVersionSemantics::WithVersion version 100\.105 required--this is only version v35\.36/,
        'numeric requirements preserve the available v-string spelling';
}

my $missing_package_error = eval {
    UseVersionSemantics::MissingPackage->VERSION(3);
    '';
} || $@;
like $missing_package_error,
    qr/UseVersionSemantics::MissingPackage defines neither package nor VERSION--version check failed/,
    'VERSION distinguishes an absent package from a missing VERSION variable';

done_testing();
