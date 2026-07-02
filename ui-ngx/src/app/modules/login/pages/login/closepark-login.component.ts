///
/// Copyright © 2016-2026 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '@core/auth/auth.service';
import { switchMap } from 'rxjs/operators';

@Component({
  selector: 'tb-closepark-login',
  template: '',
  standalone: false
})
export class CloseParkLoginComponent implements OnInit {

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private authService: AuthService
  ) {
  }

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.goToLogin();
      return;
    }

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {token: null},
      queryParamsHandling: 'merge',
      replaceUrl: true
    });

    this.authService.closeParkLogin(token).pipe(
      switchMap((loginResponse) =>
        this.authService.setUserFromJwtToken(loginResponse.token, loginResponse.refreshToken, true))
    ).subscribe({
      next: (authenticated) => {
        if (authenticated) {
          this.authService.gotoDefaultPlace(true);
        } else {
          this.goToLogin();
        }
      },
      error: () => this.goToLogin()
    });
  }

  private goToLogin(): void {
    this.router.navigateByUrl('/login', {replaceUrl: true});
  }

}
